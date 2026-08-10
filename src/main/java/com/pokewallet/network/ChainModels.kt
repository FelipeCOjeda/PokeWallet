package com.pokewallet.network

/**
 * Modelos independentes de provedor (Esplora/Blockstream, Electrum/Floresta,
 * etc.) — antes viviam dentro de [BlockstreamClient] com o formato bruto da
 * API Esplora (chain_stats/mempool_stats); movidos pra cá pra qualquer
 * [ChainDataSource] poder construir os mesmos tipos.
 */
data class AddressStats(
    val address: String,
    val confirmedSats: Long,
    val pendingSats: Long,
    val txCount: Int,
    val mempoolTxCount: Int
) {
    val balanceSats: Long get() = confirmedSats + pendingSats
    val hasActivity: Boolean get() = txCount > 0 || mempoolTxCount > 0
}

data class RemoteUtxo(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val confirmed: Boolean,
    val blockHeight: Int?
)

data class FeeEstimates(
    val fastest: Double,
    val halfHour: Double,
    val hour: Double,
    /** meta em nº de blocos -> sat/vB, direto da fonte — usado pra estimar
     *  tempo de confirmação de uma taxa arbitrária escolhida pelo usuário */
    val byBlockTarget: Map<Int, Double>
) {
    companion object {
        /** Usado quando a fonte está fora do ar — mantém a UI de taxa
         *  funcional (com estimativa aproximada) em vez de travar o envio. */
        val FALLBACK = FeeEstimates(
            fastest       = 20.0,
            halfHour      = 10.0,
            hour          = 5.0,
            byBlockTarget = mapOf(1 to 20.0, 3 to 10.0, 6 to 5.0)
        )
    }
}
