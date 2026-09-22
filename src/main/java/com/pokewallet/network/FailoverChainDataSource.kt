package com.pokewallet.network

import com.pokewallet.crypto.Network

/**
 * [ChainDataSource] que tenta [primary] primeiro (preserva a config/
 * comportamento que o usuário já escolheu — node próprio ou Blockstream) e
 * só recorre ao pool de servidores Electrum públicos ([PublicElectrumServers])
 * quando [primary] falhar. Existe especificamente pro caso real de
 * confirmação de Silent Payments (getRawTx por txid arbitrário):
 * - Floresta (Utreexo) não guarda índice de transações históricas — falha
 *   pra qualquer txid que não seja de um dos endereços próprios da carteira.
 * - Blockstream/mempool.space aplicam rate limit por IP (429 documentado).
 *
 * Só [getRawTx] passa pelo failover; todos os demais métodos da interface
 * são delegados diretamente a [primary] (delegação via `by primary`) — evita
 * que um [broadcast]/[getFeeEstimates] futuro vá parar num servidor público
 * por acidente.
 *
 * Fixa (sticky) o primeiro servidor público que respondeu com sucesso — evita
 * reconectar/re-sortear a cada chamada dentro do mesmo sync — e só troca de
 * novo se esse sticky também falhar. Tenta no máximo [MAX_FALLBACK_ATTEMPTS]
 * servidores por chamada (limita o pior caso de timeout). [close] deve ser
 * chamado ao final do sync pra fechar o socket do sticky.
 *
 * [usedFallback] fica true quando QUALQUER chamada recorreu ao pool — exposto
 * pra UI/status avisar que o usuário falou com terceiro (privacidade).
 */
class FailoverChainDataSource(
    private val primary: ChainDataSource,
    private val pool: List<PublicElectrumServers.Entry> = PublicElectrumServers.MAINNET
) : ChainDataSource by primary {

    @Volatile var usedFallback = false
        private set

    @Volatile private var sticky: ElectrumClient? = null

    private fun <T> withFailover(call: (ChainDataSource) -> T): T {
        try {
            return call(primary)
        } catch (primaryError: Exception) {
            sticky?.let { s ->
                try {
                    usedFallback = true
                    return call(s)
                } catch (_: Exception) {
                    s.close()
                    sticky = null
                }
            }
            var lastError: Exception = primaryError
            var tried = 0
            for (entry in pool.shuffled()) {
                if (tried >= MAX_FALLBACK_ATTEMPTS) break
                tried++
                val client = ElectrumClient(
                    entry.host, entry.port,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs    = READ_TIMEOUT_MS,
                    useTls           = true
                )
                try {
                    val result = call(client)
                    sticky = client
                    usedFallback = true
                    return result
                } catch (e: Exception) {
                    client.close()
                    lastError = e
                }
            }
            throw RuntimeException(
                "Fonte principal e pool público falharam (tentados $tried): ${lastError.message}", lastError
            )
        }
    }

    fun close() {
        sticky?.close()
        sticky = null
    }

    override fun getRawTx(txid: String, network: Network) =
        withFailover { it.getRawTx(txid, network) }

    companion object {
        const val MAX_FALLBACK_ATTEMPTS = 8
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 8_000
    }
}
