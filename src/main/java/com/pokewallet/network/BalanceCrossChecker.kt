package com.pokewallet.network

import com.pokewallet.crypto.Network

/**
 * Cruza o saldo reportado pela fonte configurada (tipicamente um node
 * Electrum próprio) contra a API pública (Blockstream/mempool.space), pros
 * MESMOS endereços já identificados como ativos — defesa opcional contra
 * um node malicioso/comprometido reportando saldo menor do que o real
 * (censura parcial de fundos).
 *
 * LIMITAÇÃO IMPORTANTE (documentada, não escondida): só compara endereços
 * que a fonte configurada JÁ revelou como ativos — se um node malicioso
 * omitir um endereço INTEIRO (nunca reportar atividade nele), esse
 * endereço nunca entra na lista comparada aqui, e essa omissão não é
 * detectada. Fechar esse caso exigiria repetir a varredura completa de
 * gap-limit via a API pública (dobrando o custo de rede de todo scan) —
 * fora do escopo desta checagem, que é opcional e best-effort.
 */
object BalanceCrossChecker {

    data class Result(val configuredTotalSats: Long, val publicTotalSats: Long) {
        /** Positivo = API pública reporta MAIS do que a fonte configurada — sinal de possível censura de saldo. */
        val divergenceSats: Long get() = publicTotalSats - configuredTotalSats
    }

    /**
     * Sequencial de propósito — mesma lição do scanner paralelo revertido
     * (commit b0a572d, "regressão real em teste de campo"): abrir várias
     * conexões simultâneas contra a API pública já causou problema antes
     * neste projeto.
     */
    fun check(
        addresses: List<WalletScanner.ScannedAddress>,
        network: Network,
        publicSource: ChainDataSource = BlockstreamClient
    ): Result {
        val configuredTotal = addresses.sumOf { it.stats.confirmedSats + it.stats.pendingSats }
        val publicTotal = addresses.sumOf { addr ->
            val stats = publicSource.getAddressStats(addr.address, network)
            stats.confirmedSats + stats.pendingSats
        }
        return Result(configuredTotal, publicTotal)
    }
}
