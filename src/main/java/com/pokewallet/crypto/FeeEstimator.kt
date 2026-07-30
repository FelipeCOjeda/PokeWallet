package com.pokewallet.crypto

/**
 * Estimador simples de peso para transações SegWit (P2WPKH)
 *
 * Valores aproximados:
 * - Input P2WPKH  ≈ 68 vbytes
 * - Output P2WPKH ≈ 31 vbytes
 * - Overhead      ≈ 10 vbytes
 *
 * Suficiente para fee estimation honesta.
 */
object FeeEstimator {

    fun estimateVbytes(
        inputs: Int,
        outputs: Int
    ): Int {
        require(inputs > 0)
        require(outputs > 0)

        return inputs * 68 + outputs * 31 + 10
    }

    fun estimateFee(
        inputs: Int,
        outputs: Int,
        feeRateSatPerVbyte: Long
    ): Long = estimateFee(inputs, outputs, feeRateSatPerVbyte.toDouble())

    /**
     * Taxas escolhidas pelo usuário podem ser fracionárias (mínimo permitido
     * na UI é 0.5 sat/vB) — arredonda pra CIMA (nunca pra baixo, senão a fee
     * paga ficaria abaixo da taxa que o usuário pediu).
     */
    fun estimateFee(
        inputs: Int,
        outputs: Int,
        feeRateSatPerVbyte: Double
    ): Long {
        val vbytes = estimateVbytes(inputs, outputs)
        return kotlin.math.ceil(vbytes * feeRateSatPerVbyte).toLong()
    }
}
