package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Edge cases de dust/sweep/saldo insuficiente do ChangePlanner — a
 * função que evita a wallet esvaziar em fee num envio parcial (ver
 * histórico da classe). Números calculados a partir de
 * FeeEstimator.estimateVbytes(BIP84) = inputs*68 + outputs*31 + 10,
 * feeRate 1.0 sat/vB (fee == vbytes), salvo onde indicado.
 */
class ChangePlannerTest {

    @Test
    fun sweepGeneratesNoChangeAndSpendsEverythingMinusFee() {
        // vbytes(1 input, 1 output) = 68+31+10 = 109 → fee 109
        val plan = ChangePlanner.plan(
            totalInputSats = 1000L,
            requestedAmount = null,
            sweep = true,
            inputCount = 1,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP84
        )
        assertEquals(891L, plan.sendAmount)
        assertNull(plan.changeValue)
    }

    @Test
    fun sweepBelowDustAfterFeeThrows() {
        // total 600 - fee 109 = 491 <= dust limit (546)
        assertThrows(IllegalArgumentException::class.java) {
            ChangePlanner.plan(
                totalInputSats = 600L,
                requestedAmount = null,
                sweep = true,
                inputCount = 1,
                feeRateSatPerVbyte = 1.0,
                spendType = SpendType.BIP84
            )
        }
    }

    @Test
    fun partialSendGeneratesChangeAboveDustLimit() {
        // vbytes(1 input, 2 outputs) = 68+62+10 = 140 → fee 140
        val plan = ChangePlanner.plan(
            totalInputSats = 10000L,
            requestedAmount = 5000L,
            sweep = false,
            inputCount = 1,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP84
        )
        assertEquals(5000L, plan.sendAmount)
        assertEquals(4860L, plan.changeValue)
    }

    @Test
    fun partialSendWithChangeBelowDustDiscardsChange() {
        // changeValue = 5649 - 5000 - 140 = 509, abaixo do dust limit (546)
        // — o troco vira fee em vez de criar um UTXO de poeira.
        val plan = ChangePlanner.plan(
            totalInputSats = 5649L,
            requestedAmount = 5000L,
            sweep = false,
            inputCount = 1,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP84
        )
        assertEquals(5000L, plan.sendAmount)
        assertNull(plan.changeValue)
    }

    @Test
    fun partialSendRequestedAmountBelowDustThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ChangePlanner.plan(
                totalInputSats = 10000L,
                requestedAmount = 500L,
                sweep = false,
                inputCount = 1,
                feeRateSatPerVbyte = 1.0,
                spendType = SpendType.BIP84
            )
        }
    }

    @Test
    fun partialSendInsufficientFundsThrows() {
        // fee(1 input, 1 output) = 109; 900 > 1000 - 109 = 891
        assertThrows(IllegalArgumentException::class.java) {
            ChangePlanner.plan(
                totalInputSats = 1000L,
                requestedAmount = 900L,
                sweep = false,
                inputCount = 1,
                feeRateSatPerVbyte = 1.0,
                spendType = SpendType.BIP84
            )
        }
    }

    @Test
    fun partialSendWithoutRequestedAmountThrows() {
        assertThrows(IllegalStateException::class.java) {
            ChangePlanner.plan(
                totalInputSats = 10000L,
                requestedAmount = null,
                sweep = false,
                inputCount = 1,
                feeRateSatPerVbyte = 1.0,
                spendType = SpendType.BIP84
            )
        }
    }

    @Test
    fun taprootWalletUsesLargerOutputCostThanSegwit() {
        // vbytes(1 input, 2 outputs, BIP86) = 58 + 2*43 + 10 = 154 → fee 154
        // (contra 140 no equivalente BIP84 acima) — prova que o troco de uma
        // carteira BIP86 é calculado com o custo real do output P2TR, não
        // mais a constante fixa de P2WPKH que subestimava a fee.
        val plan = ChangePlanner.plan(
            totalInputSats = 10000L,
            requestedAmount = 5000L,
            sweep = false,
            inputCount = 1,
            feeRateSatPerVbyte = 1.0,
            spendType = SpendType.BIP86
        )
        assertEquals(5000L, plan.sendAmount)
        assertEquals(4846L, plan.changeValue)
    }
}
