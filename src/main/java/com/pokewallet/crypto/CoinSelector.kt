package com.pokewallet.crypto

data class Utxo(
    val txid: ByteArray,
    val vout: Int,
    val value: Long,
    val scriptPubKey: ByteArray,
    /** Chain/index HD que controla este UTXO (0=externo/1=interno, índice) —
     *  necessário pra derivar a chave certa depois de selecionado. */
    val chain: Int = 0,
    val index: Int = 0
)

object CoinSelector {

    /**
     * Largest-first coin selection.
     */
    fun select(
        utxos: List<Utxo>,
        targetValue: Long,
        feeRateSatPerVbyte: Double,
        spendType: SpendType
    ): Pair<List<Utxo>, Long> {

        require(utxos.isNotEmpty()) {
            "Nenhum UTXO disponível"
        }

        val sorted = utxos.sortedByDescending { it.value }

        val selected = mutableListOf<Utxo>()
        var total = 0L

        for (utxo in sorted) {
            selected.add(utxo)
            total += utxo.value

            val estimatedFee = FeeEstimator.estimateFee(
                inputs = selected.size,
                outputs = 2, // pagamento + change
                spendType = spendType,
                feeRateSatPerVbyte = feeRateSatPerVbyte
            )

            if (total >= targetValue + estimatedFee) {
                val change = total - targetValue - estimatedFee
                return selected to change
            }
        }

        error("Saldo insuficiente para cobrir valor + fee")
    }
}
