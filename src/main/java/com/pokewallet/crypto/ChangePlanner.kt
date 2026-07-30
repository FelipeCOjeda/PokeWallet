package com.pokewallet.crypto

/**
 * Decide quanto vai pro destino e se sobra troco — SEM tocar em chave
 * privada nem scriptPubKey (isso fica por conta de quem chama). Isolado
 * assim de propósito: é puro cálculo de Long, testável sem depender de
 * Android/derivação de chave.
 *
 * Histórico: antes desta função existir, o envio parcial (sweep=false)
 * nunca gerava output de troco — tudo que sobrava dos inputs virava fee,
 * esvaziando a wallet inteira em taxa de mineração num envio pequeno.
 */
object ChangePlanner {

    data class Plan(
        val sendAmount: Long,
        /** null = sem troco (sweep, ou troco abaixo do dust limit) */
        val changeValue: Long?
    )

    fun plan(
        totalInputSats: Long,
        requestedAmount: Long?,
        sweep: Boolean,
        inputCount: Int,
        feeRateSatPerVbyte: Double,
        dustLimit: Long = 546L
    ): Plan {
        if (sweep) {
            val fee = FeeEstimator.estimateFee(inputCount, 1, feeRateSatPerVbyte)
            val sendAmount = totalInputSats - fee
            require(sendAmount > dustLimit) {
                "Valor ($sendAmount sat) abaixo do dust limit após taxa de $fee sat"
            }
            return Plan(sendAmount, null)
        }

        val sendAmount = requestedAmount ?: error("Valor não informado")
        require(sendAmount > dustLimit) { "Valor ($sendAmount sat) abaixo do dust limit" }

        val feeNoChange = FeeEstimator.estimateFee(inputCount, 1, feeRateSatPerVbyte)
        require(sendAmount <= totalInputSats - feeNoChange) {
            "Saldo insuficiente: $totalInputSats sat disponíveis, fee $feeNoChange sat"
        }

        // Assume 2 outputs (destino + troco) pra estimar a fee. Se o troco
        // resultante não passar do dust limit, ele é descartado (vira fee —
        // manter um UTXO de troco abaixo do dust custaria mais pra gastar
        // depois do que ele vale); a validação acima (fee de 1 output) já
        // garante que sobra o suficiente nesse caso.
        val feeWithChange = FeeEstimator.estimateFee(inputCount, 2, feeRateSatPerVbyte)
        val changeValue   = totalInputSats - sendAmount - feeWithChange

        return if (changeValue > dustLimit) Plan(sendAmount, changeValue) else Plan(sendAmount, null)
    }
}
