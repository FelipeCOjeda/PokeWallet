package com.pokewallet.crypto

/**
 * Estimador de peso pra transações SegWit v0 (P2WPKH) e Taproot key-path
 * (P2TR). Uma carteira só usa um spendType (nunca mistura os dois nos
 * próprios inputs/troco — é fixo desde a criação), então o custo real por
 * input/output depende de qual spendType a carteira usa, não é uma
 * constante única.
 *
 * Valores aproximados:
 * - Input P2WPKH  ≈ 68 vbytes   (base 41 * 4 + witness ~108) / 4, arred. cima
 * - Input P2TR    ≈ 58 vbytes   (base 41 * 4 + witness ~66,  key-path)  / 4, arred. cima
 * - Output P2WPKH ≈ 31 vbytes   (8 valor + 1 varint-len + 22 bytes de script)
 * - Output P2TR   ≈ 43 vbytes   (8 valor + 1 varint-len + 34 bytes de script)
 * - Overhead      ≈ 10 vbytes
 *
 * LIMITAÇÃO CONHECIDA: assume que todo output (destino + troco) usa o
 * mesmo tipo de script do spendType da própria carteira. Enviar pra um
 * endereço de tipo diferente (ex.: carteira BIP84 mandando pra um destino
 * P2TR) subestima a fee desse output específico em alguns vbytes — mesma
 * direção (subestimar) do comportamento anterior desta classe, só que
 * agora restrita a esse caso específico em vez de acontecer sempre.
 */
object FeeEstimator {

    private const val VBYTES_INPUT_P2WPKH  = 68
    private const val VBYTES_INPUT_P2TR    = 58
    private const val VBYTES_OUTPUT_P2WPKH = 31
    private const val VBYTES_OUTPUT_P2TR   = 43
    private const val VBYTES_OVERHEAD      = 10

    fun vbytesPerInput(spendType: SpendType): Int =
        if (spendType == SpendType.BIP86) VBYTES_INPUT_P2TR else VBYTES_INPUT_P2WPKH

    fun vbytesPerOutput(spendType: SpendType): Int =
        if (spendType == SpendType.BIP86) VBYTES_OUTPUT_P2TR else VBYTES_OUTPUT_P2WPKH

    fun estimateVbytes(
        inputs: Int,
        outputs: Int,
        spendType: SpendType
    ): Int {
        require(inputs > 0)
        require(outputs > 0)

        return inputs * vbytesPerInput(spendType) + outputs * vbytesPerOutput(spendType) + VBYTES_OVERHEAD
    }

    /**
     * Taxas escolhidas pelo usuário podem ser fracionárias (mínimo permitido
     * na UI é 0.5 sat/vB) — arredonda pra CIMA (nunca pra baixo, senão a fee
     * paga ficaria abaixo da taxa que o usuário pediu).
     */
    fun estimateFee(
        inputs: Int,
        outputs: Int,
        spendType: SpendType,
        feeRateSatPerVbyte: Double
    ): Long {
        val vbytes = estimateVbytes(inputs, outputs, spendType)
        return kotlin.math.ceil(vbytes * feeRateSatPerVbyte).toLong()
    }
}
