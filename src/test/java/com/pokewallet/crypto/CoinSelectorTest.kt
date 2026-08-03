package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Edge cases do CoinSelector (largest-first): UTXO único suficiente,
 * saldo insuficiente, e seleção acumulando vários UTXOs até cobrir
 * valor + fee. Fee calculada por FeeEstimator (68 vbytes/input, 31
 * vbytes/output fixo em 2, 10 overhead), feeRate 1 sat/vB.
 */
class CoinSelectorTest {

    private fun utxo(value: Long) = Utxo(
        txid = ByteArray(32),
        vout = 0,
        value = value,
        scriptPubKey = byteArrayOf()
    )

    @Test
    fun singleUtxoCoversTargetAndFee() {
        // fee(1 input, 2 outputs) = 68 + 62 + 10 = 140
        val (selected, change) = CoinSelector.select(
            utxos = listOf(utxo(10000L)),
            targetValue = 5000L,
            feeRateSatPerVbyte = 1L
        )
        assertEquals(1, selected.size)
        assertEquals(4860L, change)
    }

    @Test
    fun insufficientFundsThrows() {
        assertThrows(IllegalStateException::class.java) {
            CoinSelector.select(
                utxos = listOf(utxo(100L)),
                targetValue = 5000L,
                feeRateSatPerVbyte = 1L
            )
        }
    }

    @Test
    fun picksOnlyLargestUtxoWhenItAloneIsEnough() {
        val small = utxo(1000L)
        val large = utxo(9000L)
        // fee(1 input, 2 outputs) = 140
        val (selected, change) = CoinSelector.select(
            utxos = listOf(small, large),
            targetValue = 5000L,
            feeRateSatPerVbyte = 1L
        )
        assertEquals(listOf(large), selected)
        assertEquals(3860L, change)
    }

    @Test
    fun accumulatesMultipleUtxosWhenNeeded() {
        val utxos = listOf(utxo(3000L), utxo(3000L), utxo(3000L))
        // fee(3 inputs, 2 outputs) = 68*3 + 31*2 + 10 = 276
        val (selected, change) = CoinSelector.select(
            utxos = utxos,
            targetValue = 7000L,
            feeRateSatPerVbyte = 1L
        )
        assertEquals(3, selected.size)
        assertEquals(1724L, change)
    }
}
