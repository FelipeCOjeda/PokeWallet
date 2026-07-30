package com.pokewallet.crypto

/**
 * Estima o tempo de confirmação de uma taxa (sat/vB) escolhida pelo
 * usuário, a partir do mapa de metas devolvido pela API (nº de blocos
 * necessários -> sat/vB requerido pra essa meta).
 *
 * Regra fixa pedida pelo Felipe: abaixo de 1 sat/vB o aviso é sempre
 * "mais de 1 semana", em vez de tentar prever um número — nesse regime a
 * confirmação depende de aparecer, por acaso, um bloco com espaço livre
 * sobrando pra taxas mínimas, então uma extrapolação linear a partir da
 * meta de blocos (pensada pra taxas de mercado normais) ficaria irreal.
 */
object FeeTimeEstimator {

    private const val MINUTES_PER_BLOCK = 10

    fun estimate(byBlockTarget: Map<Int, Double>, selectedRate: Double): String {
        if (selectedRate < 1.0) return "Pode levar mais de 1 semana"

        val target = byBlockTarget.toSortedMap().entries
            .firstOrNull { selectedRate >= it.value }
            ?.key
            ?: byBlockTarget.keys.maxOrNull()
            ?: return "Tempo de confirmação incerto"

        return humanize(target * MINUTES_PER_BLOCK)
    }

    private fun humanize(minutes: Int): String = when {
        minutes < 60          -> "~$minutes min"
        minutes < 60 * 24     -> "~${minutes / 60}h"
        minutes < 60 * 24 * 7 -> "~${minutes / (60 * 24)} dia${if (minutes / (60 * 24) > 1) "s" else ""}"
        else                   -> "Pode levar mais de 1 semana"
    }
}
