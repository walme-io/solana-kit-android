package io.horizontalsystems.solanakit.transactions

import com.solana.actions.Action
import com.solana.core.Account
import com.solana.core.PublicKey
import com.solana.core.TransactionInstruction
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.core.TokenAccountManager
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import com.solana.networking.Network
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx2.await
import org.sol4k.Connection
import org.sol4k.RpcUrl
import org.sol4k.api.Commitment
import java.math.BigDecimal
import java.time.Instant

class TransactionManager(
    private val address: Address,
    private val storage: TransactionStorage,
    private val rpcAction: Action,
    private val tokenAccountManager: TokenAccountManager,
    private val network: Network
) {

    private val addressString = address.publicKey.toBase58()
    private val _transactionsFlow = MutableStateFlow<List<FullTransaction>>(listOf())
    val transactionsFlow: StateFlow<List<FullTransaction>> = _transactionsFlow

    fun allTransactionsFlow(incoming: Boolean?): Flow<List<FullTransaction>> = _transactionsFlow.map { txList ->
        val incoming = incoming ?: return@map txList

        txList.filter { fullTransaction ->
            hasSolTransfer(fullTransaction, incoming) || fullTransaction.tokenTransfers.any { it.tokenTransfer.incoming == incoming }
        }
    }.filter { it.isNotEmpty() }

    fun solTransactionsFlow(incoming: Boolean?): Flow<List<FullTransaction>> = _transactionsFlow.map { txList ->
        // For SOL wallet, filter to SOL transfers and remove tokenTransfers
        txList.filter { hasSolTransfer(it, incoming) }.map {
            FullTransaction(it.transaction, emptyList())
        }
    }.filter { it.isNotEmpty() }

    fun splTransactionsFlow(mintAddress: String, incoming: Boolean?): Flow<List<FullTransaction>> = _transactionsFlow.map { txList ->
        // For SPL wallet, filter to specific token and remove SOL transfer data
        txList.filter { fullTransaction ->
            hasSplTransfer(mintAddress, fullTransaction.tokenTransfers, incoming)
        }.map { fullTx ->
            val filteredTokenTransfers = fullTx.tokenTransfers.filter { it.mintAccount.address == mintAddress }
            val cleanTransaction = fullTx.transaction.copy(from = null, to = null, amount = null)
            FullTransaction(cleanTransaction, filteredTokenTransfers)
        }
    }.filter { it.isNotEmpty() }


    suspend fun getAllTransaction(incoming: Boolean?, fromHash: String?, limit: Int?): List<FullTransaction> =
        storage.getTransactions(incoming, fromHash, limit)

    suspend fun getSolTransaction(incoming: Boolean?, fromHash: String?, limit: Int?): List<FullTransaction> =
        // For SOL wallet, return transactions without tokenTransfers to avoid showing SPL tokens
        storage.getSolTransactions(incoming, fromHash, limit).map {
            FullTransaction(it.transaction, emptyList())
        }

    suspend fun getSplTransaction(mintAddress: String, incoming: Boolean?, fromHash: String?, limit: Int?): List<FullTransaction> =
        // For SPL wallet, filter to specific token and remove SOL transfer data
        storage.getSplTransactions(mintAddress, incoming, fromHash, limit).map { fullTx ->
            val filteredTokenTransfers = fullTx.tokenTransfers.filter { it.mintAccount.address == mintAddress }
            val cleanTransaction = fullTx.transaction.copy(from = null, to = null, amount = null)
            FullTransaction(cleanTransaction, filteredTokenTransfers)
        }

    suspend fun handle(syncedTransactions: List<FullTransaction>, syncedTokenAccounts: List<TokenAccount>) {
        val existingMintAddresses = mutableListOf<String>()

        if (syncedTransactions.isNotEmpty()) {
            val existingTransactionsMap = storage.getFullTransactions(syncedTransactions.map { it.transaction.hash }).groupBy { it.transaction.hash }
            val transactions = syncedTransactions.map { syncedTx ->
                val existingTx = existingTransactionsMap[syncedTx.transaction.hash]?.firstOrNull()

                if (existingTx == null) syncedTx
                else {
                    val syncedTxHeader = syncedTx.transaction
                    val existingTxHeader = existingTx.transaction

                    FullTransaction(
                        transaction = Transaction(
                            hash = syncedTxHeader.hash,
                            timestamp = syncedTxHeader.timestamp,
                            fee = syncedTxHeader.fee,
                            from = syncedTxHeader.from ?: existingTxHeader.from,
                            to = syncedTxHeader.to ?: existingTxHeader.to,
                            amount = syncedTxHeader.amount ?: existingTxHeader.amount,
                            error = syncedTxHeader.error,
                            pending = syncedTxHeader.pending,
                            ),
                        tokenTransfers = syncedTx.tokenTransfers.ifEmpty {
                            for (tokenTransfer in existingTx.tokenTransfers) {
                                existingMintAddresses.add(tokenTransfer.mintAccount.address)
                            }

                            existingTx.tokenTransfers
                        }
                    )
                }
            }

            storage.addTransactions(transactions)
            _transactionsFlow.tryEmit(transactions)
        }

        if (syncedTokenAccounts.isNotEmpty() || existingMintAddresses.isNotEmpty()) {
            tokenAccountManager.addAccount(syncedTokenAccounts.toSet().toList(), existingMintAddresses.toSet().toList())
        }
    }

    fun notifyTransactionsUpdate(transactions: List<FullTransaction>) {
        _transactionsFlow.tryEmit(transactions)
    }

    private fun hasSolTransfer(fullTransaction: FullTransaction, incoming: Boolean?): Boolean {
        val tx = fullTransaction.transaction
        val amount = tx.amount ?: return false
        if (amount <= BigDecimal.ZERO) return false

        // User must be sender or receiver
        val isUserSender = tx.from == addressString
        val isUserReceiver = tx.to == addressString
        if (!isUserSender && !isUserReceiver) return false

        val incoming = incoming ?: return true
        return (incoming && isUserReceiver) || (!incoming && isUserSender)
    }

    private fun hasSplTransfer(mintAddress: String, tokenTransfers: List<FullTokenTransfer>, incoming: Boolean?): Boolean =
        tokenTransfers.any { fullTokenTransfer ->
            if (fullTokenTransfer.mintAccount.address != mintAddress) return false
            val incoming = incoming ?: return@any true

            fullTokenTransfer.tokenTransfer.incoming == incoming
        }

    suspend fun sendSol(toAddress: Address, amount: Long, signerAccount: Account): FullTransaction {
        val rpcUrl = when (network) {
            Network.mainnetBeta -> RpcUrl.MAINNNET
            Network.devnet -> RpcUrl.DEVNET
            Network.testnet -> RpcUrl.TESTNET
        }
        val connection = Connection(rpcUrl)
        val blockHash = connection.getLatestBlockhashExtended(Commitment.FINALIZED)
        val (transactionHash, base64Encoded) = rpcAction.sendSOL(
            account = signerAccount,
            destination = toAddress.publicKey,
            amount = amount,
            instructions = priorityFeeInstructions(),
            recentBlockHash = blockHash.blockhash
        ).await()

        val fullTransaction = FullTransaction(
            Transaction(
                hash = transactionHash,
                timestamp = Instant.now().epochSecond,
                fee = SolanaKit.fee,
                from = addressString,
                to = toAddress.publicKey.toBase58(),
                amount = amount.toBigDecimal().movePointLeft(TransactionSyncer.SOL_DECIMALS),
                pending = true,
                blockHash = blockHash.blockhash,
                lastValidBlockHeight = blockHash.lastValidBlockHeight,
                base64Encoded = base64Encoded
            ),
            listOf()
        )

        storage.addTransactions(listOf(fullTransaction))
        _transactionsFlow.tryEmit(listOf(fullTransaction))

        return fullTransaction
    }

    private fun priorityFeeInstructions(): List<TransactionInstruction> {
        val computeUnitLimit = ComputeBudgetProgram.setComputeUnitLimit(units = 300_000)
        val computeUnitPrice = ComputeBudgetProgram.setComputeUnitPrice(microLamports = 500_000)
        return listOf(computeUnitLimit, computeUnitPrice)
    }

    suspend fun sendSpl(mintAddress: Address, toAddress: Address, amount: Long, signerAccount: Account): FullTransaction {
        val mintAddressString = mintAddress.publicKey.toBase58()
        val fullTokenAccount = tokenAccountManager.getFullTokenAccountByMintAddress(mintAddressString)
            ?: throw Exception("TokenAccount not found for $mintAddressString")
        val tokenAccount = fullTokenAccount.tokenAccount
        val mintAccount = fullTokenAccount.mintAccount

        val rpcUrl = when (network) {
            Network.mainnetBeta -> RpcUrl.MAINNNET
            Network.devnet -> RpcUrl.DEVNET
            Network.testnet -> RpcUrl.TESTNET
        }
        val connection = Connection(rpcUrl)
        val blockHash = connection.getLatestBlockhashExtended(Commitment.FINALIZED)

        val (transactionHash, base64Trx) = rpcAction.sendSPLTokens(
            mintAddress = mintAddress.publicKey,
            fromPublicKey = PublicKey(tokenAccount.address),
            destinationAddress = toAddress.publicKey,
            amount = amount,
            account = signerAccount,
            allowUnfundedRecipient = true,
            instructions = priorityFeeInstructions(),
            recentBlockHash = blockHash.blockhash
        ).await()

        val fullTransaction = FullTransaction(
            Transaction(
                hash = transactionHash,
                timestamp = Instant.now().epochSecond,
                fee = SolanaKit.fee,
                pending = true,
                blockHash = blockHash.blockhash,
                lastValidBlockHeight = blockHash.lastValidBlockHeight,
                base64Encoded = base64Trx
            ),
            listOf(
                FullTokenTransfer(
                    TokenTransfer(transactionHash, mintAddressString, false, amount.toBigDecimal()),
                    mintAccount
                )
            )
        )

        storage.addTransactions(listOf(fullTransaction))
        _transactionsFlow.tryEmit(listOf(fullTransaction))

        return fullTransaction
    }

}
