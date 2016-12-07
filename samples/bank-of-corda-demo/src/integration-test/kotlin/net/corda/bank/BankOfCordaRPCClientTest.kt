package net.corda.bank

import net.corda.bank.api.BOC_ISSUER_PARTY_REF
import net.corda.bank.flow.IssuerFlow.IssuanceRequester
import net.corda.client.CordaRPCClient
import net.corda.core.contracts.DOLLARS
import net.corda.core.node.services.ServiceInfo
import net.corda.core.transactions.SignedTransaction
import net.corda.node.driver.driver
import net.corda.node.services.User
import net.corda.node.services.config.configureTestSSL
import net.corda.node.services.messaging.startFlow
import net.corda.node.services.startFlowPermission
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.testing.expect
import net.corda.testing.expectEvents
import net.corda.testing.getHostAndPort
import net.corda.testing.sequence
import org.junit.Test
import kotlin.test.assertTrue

class BankOfCordaRPCClientTest {

    @Test fun `test issuer flow via RPC`() {
        driver(dsl = {
            val user = User("user1", "test", permissions = setOf(startFlowPermission<IssuanceRequester>()))
            val nodeBankOfCorda = startNode("BankOfCorda", setOf(ServiceInfo(SimpleNotaryService.type)), arrayListOf(user)).get()
            val nodeBankOfCordaApiAddr = nodeBankOfCorda.config.getHostAndPort("artemisAddress")
            val bankOfCordaParty = nodeBankOfCorda.nodeInfo.legalIdentity
            val nodeBigCorporation = startNode("BigCorporation", rpcUsers = arrayListOf(user)).get()
            val bigCorporationParty = nodeBigCorporation.nodeInfo.legalIdentity

            // Bank of Corda RPC Client
            val bocClient = CordaRPCClient(nodeBankOfCordaApiAddr, configureTestSSL())
            bocClient.start("user1","test")
            val bocProxy = bocClient.proxy()

            // Big Corporation RPC Client
            val bigCorpClient = CordaRPCClient(nodeBankOfCordaApiAddr, configureTestSSL())
            bigCorpClient.start("user1","test")
            val bigCorpProxy = bigCorpClient.proxy()

            // Register for Bank of Corda Vault updates
            val vaultUpdatesBoc = bocProxy.vaultAndUpdates().second

            // Register for Big Corporation Vault updates
            val vaultUpdatesBigCorp = bigCorpProxy.vaultAndUpdates().second

            // Kick-off actual Issuer Flow
            val result = bocProxy.startFlow(::IssuanceRequester, 1000.DOLLARS, bigCorporationParty, BOC_ISSUER_PARTY_REF, bankOfCordaParty).returnValue.toBlocking().first()
            assertTrue { result is SignedTransaction }

            // Check Bank of Corda Vault Updates
            vaultUpdatesBoc.expectEvents {
                sequence(
                        // ISSUE
                        expect { update ->
                            require(update.consumed.size == 0) { update.consumed.size }
                            require(update.produced.size == 1) { update.produced.size }
                        },
                        // MOVE
                        expect { update ->
                            require(update.consumed.size == 1) { update.consumed.size }
                            require(update.produced.size == 0) { update.produced.size }
                        }
                )
            }

            // Check Big Corporation Vault Updates
            vaultUpdatesBigCorp.expectEvents {
                sequence(
                        // ISSUE
                        expect { update ->
                            require(update.consumed.size == 0) { update.consumed.size }
                            require(update.produced.size == 1) { update.produced.size }
                        },
                        // MOVE
                        expect { update ->
                            require(update.consumed.size == 1) { update.consumed.size }
                            require(update.produced.size == 0) { update.produced.size }
                        }
                )
            }
        }, isDebug = true)
    }
}