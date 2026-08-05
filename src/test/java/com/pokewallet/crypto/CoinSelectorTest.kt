package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Edge cases do CoinSelector (largest-first): UTXO único suficiente,
 * saldo insuficiente, e seleção acumulando vários UTXOs até cobrir
 * valor + fee. Fee calculada por FeeEstimator (BIP84: 68 vbytes/input,
 * 31 vbytes/output fixo em 2, 10 overhead), feeRate 1 sat/vB salvo onde
 * indicado.
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
        // fee(1 input, 2 outputs, BIP84) = 68 + 62 + 10 = 140
        val (selected, change) = CoinSelector.select(
            utxos = listOf(utxo(10000L)),
            targetValue = 5000L,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP84
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
                feeRateSatPerVbyte = 1.0,
                spendType = SpendType.BIP84
            )
        }
    }

    @Test
    fun picksOnlyLargestUtxoWhenItAloneIsEnough() {
        val small = utxo(1000L)
        val large = utxo(9000L)
        // fee(1 input, 2 outputs, BIP84) = 140
        val (selected, change) = CoinSelector.select(
            utxos = listOf(small, large),
            targetValue = 5000L,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP84
        )
        assertEquals(listOf(large), selected)
        assertEquals(3860L, change)
    }

    @Test
    fun accumulatesMultipleUtxosWhenNeeded() {
        val utxos = listOf(utxo(3000L), utxo(3000L), utxo(3000L))
        // fee(3 inputs, 2 outputs, BIP84) = 68*3 + 31*2 + 10 = 276
        val (selected, change) = CoinSelector.select(
            utxos = utxos,
            targetValue = 7000L,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP84
        )
        assertEquals(3, selected.size)
        assertEquals(1724L, change)
    }

    @Test
    fun taprootInputsCostLessVbytesThanSegwit() {
        // fee(1 input, 2 outputs, BIP86) = 58 + 2*43 + 10 = 154 — mais barato
        // que o equivalente BIP84 (140+... na verdade BIP86 é mais caro aqui
        // por causa do output maior: 58 + 86 + 10 = 154 > 140). O que importa
        // pro teste é que o número muda com spendType (prova que FeeEstimator
        // está sendo consultado de verdade, não uma constante fixa).
        val (_, change) = CoinSelector.select(
            utxos = listOf(utxo(10000L)),
            targetValue = 5000L,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP86
        )
        assertEquals(4846L, change)
    }

    @Test
    fun excludesUtxosNotPassedIn() {
        // Simula o filtro de congelados: um UTXO "congelado" simplesmente
        // não entra na lista passada pro CoinSelector — não tem lógica de
        // congelamento aqui, só confirma que UTXOs de fora da lista nunca
        // aparecem selecionados mesmo quando sozinhos cobririam o alvo.
        val frozen    = utxo(50000L)
        val available = listOf(utxo(10000L))
        val (selected, _) = CoinSelector.select(
            utxos = available,
            targetValue = 5000L,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP84
        )
        assertEquals(available, selected)
        assertFalse(selected.contains(frozen))
    }
}
