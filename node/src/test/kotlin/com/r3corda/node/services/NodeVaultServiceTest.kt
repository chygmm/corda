package com.r3corda.node.services

import com.r3corda.contracts.asset.Cash
import com.r3corda.contracts.testing.fillWithSomeTestCash
import com.r3corda.core.contracts.DOLLARS
import com.r3corda.core.contracts.POUNDS
import com.r3corda.core.contracts.TransactionType
import com.r3corda.core.contracts.`issued by`
import com.r3corda.core.node.services.TxWritableStorageService
import com.r3corda.core.node.services.VaultService
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.core.utilities.DUMMY_NOTARY
import com.r3corda.core.utilities.LogHelper
import com.r3corda.node.services.vault.NodeVaultService
import com.r3corda.node.utilities.configureDatabase
import com.r3corda.node.utilities.databaseTransaction
import com.r3corda.testing.MEGA_CORP
import com.r3corda.testing.MEGA_CORP_KEY
import com.r3corda.testing.node.MockServices
import com.r3corda.testing.node.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.Database
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.util.*
import kotlin.test.assertEquals

class NodeVaultServiceTest {
    lateinit var dataSource: Closeable
    lateinit var database: Database

    @Before
    fun setUp() {
        LogHelper.setLevel(NodeVaultService::class)
        val dataSourceAndDatabase = configureDatabase(makeTestDataSourceProperties())
        dataSource = dataSourceAndDatabase.first
        database = dataSourceAndDatabase.second
    }

    @After
    fun tearDown() {
        dataSource.close()
        LogHelper.reset(NodeVaultService::class)
    }

    @Test
    fun `states not local to instance`() {
        databaseTransaction(database) {
            val services1 = object : MockServices() {
                override val vaultService: VaultService = NodeVaultService(this)

                override fun recordTransactions(txs: Iterable<SignedTransaction>) {
                    for (stx in txs) {
                        storageService.validatedTransactions.addTransaction(stx)
                        vaultService.notify(stx.tx)
                    }
                }
            }
            services1.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L))

            val w1 = services1.vaultService.currentVault
            assertThat(w1.states).hasSize(3)

            val originalStorage = services1.storageService
            val services2 = object : MockServices() {
                override val vaultService: VaultService = NodeVaultService(this)

                // We need to be able to find the same transactions as before, too.
                override val storageService: TxWritableStorageService get() = originalStorage

                override fun recordTransactions(txs: Iterable<SignedTransaction>) {
                    for (stx in txs) {
                        storageService.validatedTransactions.addTransaction(stx)
                        vaultService.notify(stx.tx)
                    }
                }
            }

            val w2 = services2.vaultService.currentVault
            assertThat(w2.states).hasSize(3)
        }
    }

    @Test
    fun addNoteToTransaction() {

        databaseTransaction(database) {
            val services = object : MockServices() {
                override val vaultService: VaultService = NodeVaultService(this)

                override fun recordTransactions(txs: Iterable<SignedTransaction>) {
                    for (stx in txs) {
                        storageService.validatedTransactions.addTransaction(stx)
                    }
                    // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
                    vaultService.notifyAll(txs.map { it.tx })
                }
            }

            val freshKey = services.legalIdentityKey

            // Issue a txn to Send us some Money
            val usefulTX = TransactionType.General.Builder(null).apply {
                Cash().generateIssue(this, 100.DOLLARS `issued by` MEGA_CORP.ref(1), freshKey.public, DUMMY_NOTARY)
                signWith(MEGA_CORP_KEY)
            }.toSignedTransaction()

            services.recordTransactions(listOf(usefulTX))

            services.vaultService.addNoteToTransaction(usefulTX.id, "USD Sample Note 1")
            services.vaultService.addNoteToTransaction(usefulTX.id, "USD Sample Note 2")
            services.vaultService.addNoteToTransaction(usefulTX.id, "USD Sample Note 3")
            assertEquals(1, services.vaultService.currentVault.transactionNotes.toList().size)
            assertEquals(3, services.vaultService.getTransactionNotes(usefulTX.id).count())

            // Issue more Money (GBP)
            val anotherTX = TransactionType.General.Builder(null).apply {
                Cash().generateIssue(this, 200.POUNDS `issued by` MEGA_CORP.ref(1), freshKey.public, DUMMY_NOTARY)
                signWith(MEGA_CORP_KEY)
            }.toSignedTransaction()

            services.recordTransactions(listOf(anotherTX))

            services.vaultService.addNoteToTransaction(anotherTX.id, "GPB Sample Note 1")
            assertEquals(2, services.vaultService.currentVault.transactionNotes.toList().size)
            assertEquals(1, services.vaultService.getTransactionNotes(anotherTX.id).count())
        }
    }
}