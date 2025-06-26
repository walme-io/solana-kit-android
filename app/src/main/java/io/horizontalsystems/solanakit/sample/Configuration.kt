package io.horizontalsystems.solanakit.sample

import io.horizontalsystems.solanakit.models.RpcSource
import com.solana.networking.Network

object Configuration {
    // Use `RpcSource.Devnet` for devnet testing
    val rpcSource: RpcSource = RpcSource.TritonOne
    val network: Network = rpcSource.endpoint.network
    const val solscanApiKey: String = ""
    const val walletId = "walletId"
    const val defaultsWords = ""
}
