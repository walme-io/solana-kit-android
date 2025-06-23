package io.horizontalsystems.solanakit.sample

import io.horizontalsystems.solanakit.models.RpcSource

object Configuration {
    // Use `RpcSource.Devnet` for devnet testing
    val rpcSource: RpcSource = RpcSource.TritonOne
    const val solscanApiKey: String = ""
    const val walletId = "walletId"
    const val defaultsWords = ""
}
