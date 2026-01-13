package com.pokewallet.crypto

data class Utxo(
    val txid: ByteArray,
    val vout: Int,
    val value: Long,
    val scriptPubKey: ByteArray
)

object CoinSelector {

    /**
     * Largest-first coin selection
     */
    fun select(
        utxos: List<Utxo>,
        targetValue: Long,
        feeRateSatPerVbyte: Long
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
