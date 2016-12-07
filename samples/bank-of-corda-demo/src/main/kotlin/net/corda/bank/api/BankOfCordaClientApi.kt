package net.corda.bank.api

import com.google.common.net.HostAndPort
import net.corda.bank.api.BankOfCordaWebApi.IssueRequestParams
import net.corda.bank.flow.IssuerFlow.IssuanceRequester
import net.corda.client.CordaRPCClient
import net.corda.core.contracts.Amount
import net.corda.core.contracts.currency
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.loggerFor
import net.corda.node.services.config.configureTestSSL
import net.corda.node.services.messaging.startFlow
import net.corda.testing.http.HttpApi

/**
 * Interface for communicating with Bank of Corda node
 */
class BankOfCordaClientApi(val hostAndPort: HostAndPort) {
    private val apiRoot = "api/bank"
    /**
     * HTTP API
     */
    // TODO: security controls required
    fun requestWebIssue(params: IssueRequestParams): Boolean {
        val api = HttpApi.fromHostAndPort(hostAndPort, apiRoot)
        return api.postJson("issue-asset-request", params)
    }
    /**
     * RPC API
     */
    fun requestRPCIssue(params: IssueRequestParams): SignedTransaction {
        val client = CordaRPCClient(hostAndPort, configureTestSSL())
        // TODO: privileged security controls required
        client.start("user1","test")
        val proxy = client.proxy()

        // TODO: this will change following Web-RPC'fication
        // Resolve parties using the network map
        val networkMap = proxy.networkMapUpdates().first
        val issueToNodeInfo = networkMap.find { it -> it.legalIdentity.name == params.issueToPartyName }
                ?: throw Exception("Unable to locate ${params.issueToPartyName} in Network Map Service")
        val issueToParty = issueToNodeInfo.legalIdentity
        val issuerBankNodeInfo = networkMap.find { it -> it.legalIdentity.name == params.issuerBankName }
                ?: throw Exception("Unable to locate ${params.issuerBankName} in Network Map Service")
        val issuerBankParty = issuerBankNodeInfo.legalIdentity

        val amount = Amount(params.amount, currency(params.currency))
        val issuerToPartyRef = OpaqueBytes.of(params.issueToPartyRefAsString.toByte())

        return proxy.startFlow(::IssuanceRequester, amount, issueToParty, issuerToPartyRef, issuerBankParty).returnValue.toBlocking().first()
    }
}
