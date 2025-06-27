package io.horizontalsystems.solanakit.sample

import io.horizontalsystems.solanakit.models.RpcSource
import com.solana.networking.Network

object Configuration {
    // Default RPC source used by the sample application.
    // Change to `RpcSource.TritonOne` or another source for mainnet.
    val rpcSource: RpcSource = RpcSource.Devnet
    val network: Network = rpcSource.endpoint.network
    // Insert your Solscan API key below.
    // The sample will start syncing once a valid key is provided.
    const val solscanApiKey: String = "<YOUR_SOLSCAN_API_KEY>"
    const val walletId = "walletId"
    const val defaultsWords = ""
}
