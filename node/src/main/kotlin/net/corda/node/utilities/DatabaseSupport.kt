package net.corda.node.utilities

import co.paralleluniverse.strands.Strand
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.parsePublicKeyBase58
import net.corda.core.crypto.toBase58String
import net.corda.node.utilities.StrandLocalTransactionManager.Boundary
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionInterface
import org.jetbrains.exposed.sql.transactions.TransactionManager
import rx.Observable
import rx.subjects.PublishSubject
import java.io.Closeable
import java.security.PublicKey
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Table prefix for all tables owned by the node module.
 */
const val NODE_DATABASE_PREFIX = "node_"

// TODO: Handle commit failure due to database unavailable.  Better to shutdown and await database reconnect/recovery.
fun <T> databaseTransaction(db: Database, statement: Transaction.() -> T): T {
    // We need to set the database for the current [Thread] or [Fiber] here as some tests share threads across databases.
    StrandLocalTransactionManager.database = db
    return org.jetbrains.exposed.sql.transactions.transaction(Connection.TRANSACTION_REPEATABLE_READ, 1, statement)
}

fun createDatabaseTransaction(db: Database): Transaction {
    // We need to set the database for the current [Thread] or [Fiber] here as some tests share threads across databases.
    StrandLocalTransactionManager.database = db
    return TransactionManager.currentOrNew(Connection.TRANSACTION_REPEATABLE_READ)
}

fun configureDatabase(props: Properties): Pair<Closeable, Database> {
    val config = HikariConfig(props)
    val dataSource = HikariDataSource(config)
    val database = Database.connect(dataSource) { db -> StrandLocalTransactionManager(db) }
    // Check not in read-only mode.
    databaseTransaction(database) {
        check(!database.metadata.isReadOnly) { "Database should not be readonly." }
    }
    return Pair(dataSource, database)
}

fun <T> isolatedTransaction(database: Database, block: Transaction.() -> T): T {
    val oldContext = StrandLocalTransactionManager.setThreadLocalTx(null)
    return try {
        databaseTransaction(database, block)
    } finally {
        StrandLocalTransactionManager.restoreThreadLocalTx(oldContext)
    }
}

/**
 * A relatively close copy of the [ThreadLocalTransactionManager] in Exposed but with the following adjustments to suit
 * our environment:
 *
 * Because the construction of a [Database] instance results in replacing the singleton [TransactionManager] instance,
 * our tests involving two [MockNode]s effectively replace the database instances of each other and continue to trample
 * over each other.  So here we use a companion object to hold them as [ThreadLocal] and [StrandLocalTransactionManager]
 * is otherwise effectively stateless so it's replacement does not matter.  The [ThreadLocal] is then set correctly and
 * explicitly just prior to initiating a transaction in [databaseTransaction] and [createDatabaseTransaction] above.
 *
 * The [StrandLocalTransactionManager] instances have an [Observable] of the transaction close [Boundary]s which
 * facilitates the use of [Observable.afterDatabaseCommit] to create event streams that only emit once the database
 * transaction is closed and the data has been persisted and becomes visible to other observers.
 */
class StrandLocalTransactionManager(initWithDatabase: Database) : TransactionManager {

    companion object {
        private val threadLocalDb = ThreadLocal<Database>()
        private val threadLocalTx = ThreadLocal<Transaction>()
        private val databaseToInstance = ConcurrentHashMap<Database, StrandLocalTransactionManager>()

        fun setThreadLocalTx(tx: Transaction?): Pair<Database?, Transaction?> {
            val oldTx = threadLocalTx.get()
            threadLocalTx.set(tx)
            return Pair(threadLocalDb.get(), oldTx)
        }

        fun restoreThreadLocalTx(context: Pair<Database?, Transaction?>) {
            threadLocalDb.set(context.first)
            threadLocalTx.set(context.second)
        }

        var database: Database
            get() = threadLocalDb.get() ?: throw IllegalStateException("Was expecting to find database set on current strand: ${Strand.currentStrand()}")
            set(value: Database) {
                threadLocalDb.set(value)
            }

        val manager: StrandLocalTransactionManager get() = databaseToInstance[database]!!

        val transactionBoundaries: PublishSubject<Boundary> get() = manager._transactionBoundaries
    }

    object Boundary

    private val _transactionBoundaries = PublishSubject.create<Boundary>()

    init {
        // Found a unit test that was forgetting to close the database transactions.  When you close() on the top level
        // database transaction it will reset the threadLocalTx back to null, so if it isn't then there is still a
        // databae transaction open.  The [databaseTransaction] helper above handles this in a finally clause for you
        // but any manual database transaction management is liable to have this problem.
        if (threadLocalTx.get() != null) {
            throw IllegalStateException("Was not expecting to find existing database transaction on current strand when setting database: ${Strand.currentStrand()}, ${threadLocalTx.get()}")
        }
        database = initWithDatabase
        databaseToInstance[database] = this
    }

    override fun newTransaction(isolation: Int): Transaction = Transaction(StrandLocalTransaction(database, isolation, threadLocalTx, transactionBoundaries)).apply {
        threadLocalTx.set(this)
    }

    override fun currentOrNull(): Transaction? = threadLocalTx.get()

    // Direct copy of [ThreadLocalTransaction].
    private class StrandLocalTransaction(override val db: Database, isolation: Int, val threadLocal: ThreadLocal<Transaction>, val transactionBoundaries: PublishSubject<Boundary>) : TransactionInterface {

        override val connection: Connection by lazy(LazyThreadSafetyMode.NONE) {
            db.connector().apply {
                autoCommit = false
                transactionIsolation = isolation
            }
        }

        override val outerTransaction = threadLocal.get()

        override fun commit() {
            connection.commit()
        }

        override fun rollback() {
            if (!connection.isClosed) {
                connection.rollback()
            }
        }

        override fun close() {
            connection.close()
            threadLocal.set(outerTransaction)
            if (outerTransaction == null) {
                transactionBoundaries.onNext(Boundary)
            }
        }
    }
}

/**
 * Buffer observations until after the current or next database transaction has been committed.  Observations are never
 * dropped, simply delayed.
 *
 * Primarily for use by component authors to offer [Observable]s without race conditions vs. the database.
 *
 * Detail: Those outside the node should be notified after any [Flow] has checkpointed and the associated database transaction
 * has been committed so that changes are externally visible.  To do so for any [Observable], use this extension function.
 * Otherwise the state associated with the observation might not yet have changed (been committed) to reflect it.
 * If within the node, then it's okay to execute immediately within the same database transaction and therefore this
 * extension function should not be used.
 *
 * For examples, see the call hierarchy of this function.
 */
fun <T : Any> Observable<T>.afterDatabaseCommit(): Observable<T> {
    val databaseTxBoundaries: Observable<StrandLocalTransactionManager.Boundary> = StrandLocalTransactionManager.transactionBoundaries
    return this.buffer(databaseTxBoundaries).concatMap { Observable.from(it) }
}

// Composite columns for use with below Exposed helpers.
data class PartyColumns(val name: Column<String>, val owningKey: Column<CompositeKey>)
data class StateRefColumns(val txId: Column<SecureHash>, val index: Column<Int>)
data class TxnNoteColumns(val txId: Column<SecureHash>, val note: Column<String>)

/**
 * [Table] column helpers for use with Exposed, as per [varchar] etc.
 */
fun Table.publicKey(name: String) = this.registerColumn<PublicKey>(name, PublicKeyColumnType)

fun Table.compositeKey(name: String) = this.registerColumn<CompositeKey>(name, CompositeKeyColumnType)
fun Table.secureHash(name: String) = this.registerColumn<SecureHash>(name, SecureHashColumnType)
fun Table.party(nameColumnName: String, keyColumnName: String) = PartyColumns(this.varchar(nameColumnName, length = 255), this.compositeKey(keyColumnName))
fun Table.uuidString(name: String) = this.registerColumn<UUID>(name, UUIDStringColumnType)
fun Table.localDate(name: String) = this.registerColumn<LocalDate>(name, LocalDateColumnType)
fun Table.localDateTime(name: String) = this.registerColumn<LocalDateTime>(name, LocalDateTimeColumnType)
fun Table.instant(name: String) = this.registerColumn<Instant>(name, InstantColumnType)
fun Table.stateRef(txIdColumnName: String, indexColumnName: String) = StateRefColumns(this.secureHash(txIdColumnName), this.integer(indexColumnName))
fun Table.txnNote(txIdColumnName: String, txnNoteColumnName: String) = TxnNoteColumns(this.secureHash(txIdColumnName), this.text(txnNoteColumnName))

/**
 * [ColumnType] for marshalling to/from database on behalf of [PublicKey].
 */
object PublicKeyColumnType : ColumnType() {
    override fun sqlType(): String = "VARCHAR(255)"

    override fun valueFromDB(value: Any): Any = parsePublicKeyBase58(value.toString())

    override fun notNullValueToDB(value: Any): Any = if (value is PublicKey) value.toBase58String() else value
}

/**
 * [ColumnType] for marshalling to/from database on behalf of [CompositeKey].
 */
object CompositeKeyColumnType : ColumnType() {
    override fun sqlType(): String = "VARCHAR"
    override fun valueFromDB(value: Any): Any = CompositeKey.parseFromBase58(value.toString())
    override fun notNullValueToDB(value: Any): Any = if (value is CompositeKey) value.toBase58String() else value
}

/**
 * [ColumnType] for marshalling to/from database on behalf of [SecureHash].
 */
object SecureHashColumnType : ColumnType() {
    override fun sqlType(): String = "VARCHAR(64)"

    override fun valueFromDB(value: Any): Any = SecureHash.parse(value.toString())

    override fun notNullValueToDB(value: Any): Any = if (value is SecureHash) value.toString() else value
}

/**
 * [ColumnType] for marshalling to/from database on behalf of [UUID], always using a string representation.
 */
object UUIDStringColumnType : ColumnType() {
    override fun sqlType(): String = "VARCHAR(36)"

    override fun valueFromDB(value: Any): Any = UUID.fromString(value.toString())

    override fun notNullValueToDB(value: Any): Any = if (value is UUID) value.toString() else value
}

/**
 * [ColumnType] for marshalling to/from database on behalf of [java.time.LocalDate].
 */
object LocalDateColumnType : ColumnType() {
    override fun sqlType(): String = "DATE"

    override fun nonNullValueToString(value: Any): String {
        if (value is String) return value

        val localDate = when (value) {
            is LocalDate -> value
            is java.sql.Date -> value.toLocalDate()
            is java.sql.Timestamp -> value.toLocalDateTime().toLocalDate()
            else -> error("Unexpected value: $value")
        }
        return "'$localDate'"
    }

    override fun valueFromDB(value: Any): Any = when (value) {
        is java.sql.Date -> value.toLocalDate()
        is java.sql.Timestamp -> value.toLocalDateTime().toLocalDate()
        is Long -> LocalDate.from(Instant.ofEpochMilli(value))
        else -> value
    }

    override fun notNullValueToDB(value: Any): Any = if (value is LocalDate) {
        java.sql.Date(value.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    } else value
}


/**
 * [ColumnType] for marshalling to/from database on behalf of [java.time.LocalDateTime].
 */
object LocalDateTimeColumnType : ColumnType() {
    private val sqlType = DateColumnType(time = true).sqlType()
    override fun sqlType(): String = sqlType

    override fun nonNullValueToString(value: Any): String {
        if (value is String) return value

        val localDateTime = when (value) {
            is LocalDateTime -> value
            is java.sql.Date -> value.toLocalDate().atStartOfDay()
            is java.sql.Timestamp -> value.toLocalDateTime()
            else -> error("Unexpected value: $value")
        }
        return "'$localDateTime'"
    }

    override fun valueFromDB(value: Any): Any = when (value) {
        is java.sql.Date -> value.toLocalDate().atStartOfDay()
        is java.sql.Timestamp -> value.toLocalDateTime()
        is Long -> LocalDateTime.from(Instant.ofEpochMilli(value))
        else -> value
    }

    override fun notNullValueToDB(value: Any): Any = if (value is LocalDateTime) {
        java.sql.Timestamp(value.toInstant(ZoneOffset.UTC).toEpochMilli())
    } else value
}


/**
 * [ColumnType] for marshalling to/from database on behalf of [java.time.Instant].
 */
object InstantColumnType : ColumnType() {
    private val sqlType = DateColumnType(time = true).sqlType()
    override fun sqlType(): String = sqlType

    override fun nonNullValueToString(value: Any): String {
        if (value is String) return value

        val localDateTime = when (value) {
            is Instant -> value
            is java.sql.Date -> value.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
            is java.sql.Timestamp -> value.toLocalDateTime().toInstant(ZoneOffset.UTC)
            else -> error("Unexpected value: $value")
        }
        return "'$localDateTime'"
    }

    override fun valueFromDB(value: Any): Any = when (value) {
        is java.sql.Date -> value.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
        is java.sql.Timestamp -> value.toLocalDateTime().toInstant(ZoneOffset.UTC)
        is Long -> LocalDateTime.from(Instant.ofEpochMilli(value)).toInstant(ZoneOffset.UTC)
        else -> value
    }

    override fun notNullValueToDB(value: Any): Any = if (value is Instant) {
        java.sql.Timestamp(value.toEpochMilli())
    } else value
}
