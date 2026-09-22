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
 * - Blockstream/mempool.space aplicam rate limit por IP (429 documentado,
 *   ver WalletViewModel) — um scan de Silent Payments com vários candidatos
 *   no mesmo range de blocos pode facilmente estourar isso.
 *
 * Fixa (sticky) o primeiro servidor público que respondeu com sucesso —
 * evita reconectar/re-sortear a cada chamada dentro do mesmo sync — e só
 * troca de novo se esse sticky também falhar. [close] deve ser chamado ao
 * final do sync pra fechar o socket do sticky, se algum foi aberto.
 */
class FailoverChainDataSource(
    private val primary: ChainDataSource,
    private val pool: List<PublicElectrumServers.Entry> = PublicElectrumServers.MAINNET
) : ChainDataSource {

    @Volatile private var sticky: ElectrumClient? = null

    private fun <T> withFailover(call: (ChainDataSource) -> T): T {
        try {
            return call(primary)
        } catch (primaryError: Exception) {
            sticky?.let { s ->
                try {
                    return call(s)
                } catch (_: Exception) {
                    s.close()
                    sticky = null
                }
            }
            var lastError: Exception = primaryError
            for (entry in pool.shuffled()) {
                val client = ElectrumClient(entry.host, entry.port, connectTimeoutMs = 5_000, readTimeoutMs = 10_000, useTls = true)
                try {
                    val result = call(client)
                    sticky = client
                    return result
                } catch (e: Exception) {
                    client.close()
                    lastError = e
                }
            }
            throw RuntimeException(
                "Fonte principal e ${pool.size} servidores públicos falharam: ${lastError.message}", lastError
            )
        }
    }

    fun close() {
        sticky?.close()
        sticky = null
    }

    override fun getAddressStats(address: String, network: Network) = withFailover { it.getAddressStats(address, network) }
    override fun getUtxos(address: String, network: Network) = withFailover { it.getUtxos(address, network) }
    override fun getFeeEstimates(network: Network) = withFailover { it.getFeeEstimates(network) }
    override fun broadcast(rawHex: String, network: Network) = withFailover { it.broadcast(rawHex, network) }
    override fun getRawTx(txid: String, network: Network) = withFailover { it.getRawTx(txid, network) }
    override fun getTipHeight(network: Network) = withFailover { it.getTipHeight(network) }
}
