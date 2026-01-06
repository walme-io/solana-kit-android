package io.horizontalsystems.solanakit.transactions

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import com.solana.networking.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Helius API client for fetching Solana transaction history.
 * Replaces SolscanClient with more reliable and feature-rich API.
 *
 * API Documentation: https://docs.helius.dev/solana-apis/enhanced-transactions-api
 */
class HeliusClient(
    private val apiKey: String,
    debug: Boolean,
    network: Network
) {
    private val baseUrl = when (network) {
        Network.mainnetBeta -> "https://api-mainnet.helius-rpc.com"
        Network.devnet -> "https://api-devnet.helius-rpc.com"
        Network.testnet -> "https://api-devnet.helius-rpc.com" // Helius uses devnet for testnet
    }

    val syncSourceName = "helius.dev/${network.name}/transactions"

    private val httpClient = httpClient(debug)

    /**
     * Fetches all transfers (SOL + SPL) for an account.
     * Helius returns unified data for both native and token transfers.
     */
    suspend fun getTransfers(account: String, lastTransactionHash: String?): List<HeliusTransaction> {
        val transactions = mutableListOf<HeliusTransaction>()
        var beforeSignature: String? = null
        var page = 0

        do {
            val chunk = getTransfersChunk(account, beforeSignature)

            if (chunk.isEmpty()) break

            val index = lastTransactionHash?.let { hash ->
                chunk.indexOfFirst { it.signature == hash }
            }

            if (lastTransactionHash != null && index != null && index >= 0) {
                transactions.addAll(chunk.subList(0, index))
                break
            } else {
                transactions.addAll(chunk)
            }

            beforeSignature = chunk.lastOrNull()?.signature
            page += 1
        } while (chunk.size == MAX_TRANSACTIONS_LIMIT && page < MAX_PAGES_SYNCED)

        return transactions
    }

    /**
     * For backward compatibility - returns SOL transfers only
     */
    suspend fun solTransfers(account: String, lastSolTransferHash: String?): List<SolscanTransaction> {
        val heliusTransactions = getTransfers(account, lastSolTransferHash)
        return heliusTransactions
            .filter { it.hasNativeTransfer }
            .map { it.toSolscanTransaction() }
    }

    /**
     * For backward compatibility - returns SPL transfers only
     */
    suspend fun splTransfers(account: String, lastSplTransferHash: String?): List<SolscanTransaction> {
        val heliusTransactions = getTransfers(account, lastSplTransferHash)
        return heliusTransactions
            .filter { it.hasTokenTransfer }
            .flatMap { tx -> tx.toSplSolscanTransactions() }
    }

    private suspend fun getTransfersChunk(account: String, beforeSignature: String?): List<HeliusTransaction> {
        val urlBuilder = StringBuilder("$baseUrl/v0/addresses/$account/transactions?api-key=$apiKey&limit=$MAX_TRANSACTIONS_LIMIT")

        beforeSignature?.let {
            urlBuilder.append("&before-signature=$it")
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .build()

        return suspendCoroutine { continuation ->
            try {
                val response = httpClient.newCall(request).execute()
                val responseBody: ResponseBody? = response.body

                if (!response.isSuccessful || responseBody == null) {
                    continuation.resumeWithException(
                        RuntimeException("Helius API error: ${response.code} ${response.message}")
                    )
                    return@suspendCoroutine
                }

                val jsonArray = JSONArray(responseBody.string())
                val transactions = mutableListOf<HeliusTransaction>()

                for (i in 0 until jsonArray.length()) {
                    val txObject = jsonArray.getJSONObject(i)
                    transactions.add(parseTransaction(txObject))
                }

                continuation.resume(transactions)
            } catch (e: IOException) {
                continuation.resumeWithException(RuntimeException(e))
            } catch (e: Exception) {
                continuation.resumeWithException(RuntimeException("Failed to parse Helius response: ${e.message}", e))
            }
        }
    }

    private fun parseTransaction(json: JSONObject): HeliusTransaction {
        val signature = json.getString("signature")
        val timestamp = json.optLong("timestamp", 0)
        val fee = json.optLong("fee", 0)
        val feePayer = json.optString("feePayer", "")
        val type = json.optString("type", "UNKNOWN")
        val description = json.optString("description", "")

        // Parse native transfers
        val nativeTransfers = mutableListOf<NativeTransfer>()
        val nativeTransfersArray = json.optJSONArray("nativeTransfers")
        if (nativeTransfersArray != null) {
            for (i in 0 until nativeTransfersArray.length()) {
                val transfer = nativeTransfersArray.getJSONObject(i)
                nativeTransfers.add(
                    NativeTransfer(
                        fromUserAccount = transfer.optString("fromUserAccount", ""),
                        toUserAccount = transfer.optString("toUserAccount", ""),
                        amount = transfer.optLong("amount", 0)
                    )
                )
            }
        }

        // Parse token transfers
        val tokenTransfers = mutableListOf<TokenTransferHelius>()
        val tokenTransfersArray = json.optJSONArray("tokenTransfers")
        if (tokenTransfersArray != null) {
            for (i in 0 until tokenTransfersArray.length()) {
                val transfer = tokenTransfersArray.getJSONObject(i)
                tokenTransfers.add(
                    TokenTransferHelius(
                        fromUserAccount = transfer.optString("fromUserAccount", ""),
                        toUserAccount = transfer.optString("toUserAccount", ""),
                        fromTokenAccount = transfer.optString("fromTokenAccount", ""),
                        toTokenAccount = transfer.optString("toTokenAccount", ""),
                        tokenAmount = transfer.optDouble("tokenAmount", 0.0),
                        mint = transfer.optString("mint", ""),
                        tokenStandard = transfer.optString("tokenStandard", "")
                    )
                )
            }
        }

        return HeliusTransaction(
            signature = signature,
            timestamp = timestamp,
            fee = fee,
            feePayer = feePayer,
            type = type,
            description = description,
            nativeTransfers = nativeTransfers,
            tokenTransfers = tokenTransfers
        )
    }

    private fun httpClient(debug: Boolean): OkHttpClient {
        val client = OkHttpClient.Builder()

        if (debug) {
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BODY
            client.addInterceptor(logging)
        }

        return client.build()
    }

    companion object {
        const val MAX_TRANSACTIONS_LIMIT = 100 // Helius supports up to 100
        const val MAX_PAGES_SYNCED = 10
    }
}

/**
 * Helius transaction data model
 */
data class HeliusTransaction(
    val signature: String,
    val timestamp: Long,
    val fee: Long,
    val feePayer: String,
    val type: String,
    val description: String,
    val nativeTransfers: List<NativeTransfer>,
    val tokenTransfers: List<TokenTransferHelius>
) {
    val hasNativeTransfer: Boolean
        get() = nativeTransfers.isNotEmpty()

    val hasTokenTransfer: Boolean
        get() = tokenTransfers.isNotEmpty()

    /**
     * Convert to SolscanTransaction for backward compatibility (SOL transfer)
     */
    fun toSolscanTransaction(): SolscanTransaction {
        val nativeTransfer = nativeTransfers.firstOrNull()
        return SolscanTransaction(
            hash = signature,
            blockTime = timestamp,
            fee = fee.toString(),
            solTransferSource = nativeTransfer?.fromUserAccount,
            solTransferDestination = nativeTransfer?.toUserAccount,
            solAmount = nativeTransfer?.amount
        )
    }

    /**
     * Convert to list of SolscanTransaction for backward compatibility (SPL transfers)
     * One transaction can have multiple token transfers
     */
    fun toSplSolscanTransactions(): List<SolscanTransaction> {
        return tokenTransfers.map { transfer ->
            SolscanTransaction(
                hash = signature,
                blockTime = timestamp,
                fee = fee.toString(),
                tokenAccountAddress = transfer.toTokenAccount.ifEmpty { transfer.fromTokenAccount },
                mintAccountAddress = transfer.mint,
                splBalanceChange = transfer.tokenAmount.toLong().toString()
            )
        }
    }
}

data class NativeTransfer(
    val fromUserAccount: String,
    val toUserAccount: String,
    val amount: Long
)

data class TokenTransferHelius(
    val fromUserAccount: String,
    val toUserAccount: String,
    val fromTokenAccount: String,
    val toTokenAccount: String,
    val tokenAmount: Double,
    val mint: String,
    val tokenStandard: String
)
