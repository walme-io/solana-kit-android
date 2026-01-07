package io.horizontalsystems.solanakit.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

/**
 * Represents a user's token account on Solana.
 *
 * @property mintAddress Primary key - the SPL token mint address (one per token type per wallet)
 * @property address The Associated Token Account (ATA) address
 * @property balance Token balance in smallest units (before decimal adjustment)
 * @property decimals Number of decimals for this token
 */
@Entity
data class TokenAccount(
    @PrimaryKey
    val mintAddress: String,
    val address: String,
    val balance: BigDecimal,
    val decimals: Int
) {

    override fun equals(other: Any?): Boolean {
        return (other as? TokenAccount)?.let { it.mintAddress == mintAddress } ?: false
    }

    override fun hashCode(): Int {
        return mintAddress.hashCode()
    }

}
