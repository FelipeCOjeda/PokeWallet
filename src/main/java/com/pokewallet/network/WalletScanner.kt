package com.pokewallet.network

import com.pokewallet.crypto.Network
import com.pokewallet.crypto.SpendType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Varre uma HD wallet via Blockstream API usando BIP44 gap limit.
 *
 * Algoritmo:
 *   Para cada chain (externa=0, interna=1):
 *     Deriva endereços sequencialmente a partir do xpub.
 *     Para quando encontra [gapLimit] endereços consecutivos sem atividade.
 *   Coleta UTXOs de todos os endereços com atividade.
 *   Retorna saldo total + próximos índices.
 *
 * Não depende de Bitcoin Core. Funciona em qualquer plataforma (JVM, Android).
 */
object WalletScanner {

    const val GAP_LIMIT_DEFAULT = 20

    /**
     * Quantos endereços buscar em paralelo por vez dentro de uma chain.
     * Balanceia velocidade (menos round-trips sequenciais) com rate
     * limiting da API pública da Blockstream — batch grande demais
     * arrisca 429/timeout.
     */
    private const val SCAN_BATCH_SIZE = 8

    // ── Models ────────────────────────────────────────────

    data class ScannedAddress(
        val chain: Int,                           // 0 = externo, 1 = interno
        val index: Int,
        val address: String,
        val stats: BlockstreamClient.AddressStats,
        val utxos: List<BlockstreamClient.Utxo>
    ) {
        val balanceSats: Long get() = utxos.sumOf { it.valueSats }
    }

    data class ScanResult(
        val network: Network,
        val totalSats: Long,
        val addressesWithFunds: List<ScannedAddress>,
        val allWithActivity: List<ScannedAddress>,
        val nextExternalIndex: Int,
        val nextInternalIndex: Int,
        val totalScanned: Int
    ) {
        val totalBtc: Double get() = totalSats / 100_000_000.0

        fun printSummary() {
            val confirmedSats = addressesWithFunds.sumOf { a -> a.stats.confirmedSats }
            val pendingSats   = addressesWithFunds.sumOf { a -> a.stats.pendingSats }
            val confirmedBtc  = confirmedSats / 100_000_000.0
            val pendingBtc    = pendingSats   / 100_000_000.0

            println()
            println("╔══════════════════════════════════════════╗")
            println("║         PokéWallet — Saldo Online        ║")
            println("╠══════════════════════════════════════════╣")
            println("║  Rede       : ${network.name.padEnd(27)}║")
            println("║  Confirmado : ${"%.8f BTC".format(confirmedBtc).padEnd(27)}║")
            println("║  Pendente   : ${"%.8f BTC".format(pendingBtc).padEnd(27)}║")
            println("║  Total      : ${"%.8f BTC".format(confirmedBtc + pendingBtc).padEnd(27)}║")
            println("║  UTXOs      : ${"${addressesWithFunds.sumOf { it.utxos.size }}".padEnd(27)}║")
            println("╠══════════════════════════════════════════╣")
            println("║  Próx. endereço externo : ${nextExternalIndex.toString().padEnd(15)}║")
            println("║  Próx. endereço interno : ${nextInternalIndex.toString().padEnd(15)}║")
            println("║  Endereços varridos     : ${totalScanned.toString().padEnd(15)}║")
            println("╚══════════════════════════════════════════╝")

            if (addressesWithFunds.isNotEmpty()) {
                println()
                println("UTXOs encontrados:")
                for (addr in addressesWithFunds) {
                    val chainLabel = if (addr.chain == 0) "ext" else "int"
                    println("  [$chainLabel/${addr.index}] ${addr.address}")
                    for (utxo in addr.utxos) {
                        val conf = if (utxo.confirmed) "✓ bloco ${utxo.blockHeight}" else "⏳ mempool"
                        println("    ${utxo.valueSats} sat — ${utxo.txid.take(16)}… ($conf)")
                    }
                }
            }
        }
    }

    // ── Scanner principal ─────────────────────────────────

    /**
     * @param xpub      Account-level xpub (m/84'/coin'/0')
     * @param network   Rede alvo
     * @param gapLimit  Endereços consecutivos sem uso antes de parar (padrão: 20)
     * @param onProgress Callback opcional chamado a cada endereço varrido
     */
    suspend fun scan(
        xpub: String,
        network: Network,
        spendType: SpendType = SpendType.BIP84,
        gapLimit: Int = GAP_LIMIT_DEFAULT,
        onProgress: ((chain: Int, index: Int, address: String) -> Unit)? = null
    ): ScanResult = coroutineScope {
        // Chains externa e interna são independentes — varrem em paralelo.
        val externalDeferred = async { scanChain(xpub, network, spendType, chain = 0, gapLimit, onProgress) }
        val internalDeferred = async { scanChain(xpub, network, spendType, chain = 1, gapLimit, onProgress) }
        val external = externalDeferred.await()
        val internal = internalDeferred.await()

        val all       = external.scanned + internal.scanned
        val withFunds = all.filter { it.utxos.isNotEmpty() }
        val activity  = all.filter { it.stats.hasActivity }
        val totalSats = withFunds.sumOf { it.balanceSats }

        ScanResult(
            network             = network,
            totalSats           = totalSats,
            addressesWithFunds  = withFunds,
            allWithActivity     = activity,
            nextExternalIndex   = external.nextIndex,
            nextInternalIndex   = internal.nextIndex,
            totalScanned        = all.size
        )
    }

    // ── Varredura por chain ───────────────────────────────

    private data class ChainResult(val scanned: List<ScannedAddress>, val nextIndex: Int)

    /**
     * Varre endereços em lotes de até SCAN_BATCH_SIZE, buscados em paralelo
     * (async/awaitAll) — dentro de cada lote a ordem de chegada não importa,
     * mas os resultados são processados na ordem do índice (garantida pelo
     * .map preservar a ordem dos Deferred) pra manter exatamente a mesma
     * lógica sequencial de gap limit de antes (reseta em atividade, incrementa
     * em endereço vazio, para quando atinge gapLimit consecutivos).
     */
    private suspend fun scanChain(
        xpub: String,
        network: Network,
        spendType: SpendType,
        chain: Int,
        gapLimit: Int,
        onProgress: ((Int, Int, String) -> Unit)?
    ): ChainResult = coroutineScope {
        val scanned = mutableListOf<ScannedAddress>()
        var gap = 0
        var lastUsed = -1
        var nextIndex = 0

        while (gap < gapLimit) {
            val batchSize = minOf(SCAN_BATCH_SIZE, gapLimit - gap)
            val batchStart = nextIndex

            val batch = (batchStart until batchStart + batchSize).map { index ->
                async(Dispatchers.IO) {
                    val address = when (spendType) {
                        SpendType.BIP84 -> XpubAddressDeriver.p2wpkhAddress(xpub, chain, index, network)
                        SpendType.BIP86 -> XpubAddressDeriver.p2trAddress(xpub, chain, index, network)
                    }
                    onProgress?.invoke(chain, index, address)

                    val stats = BlockstreamClient.getAddressStats(address, network)
                    val utxos = if (stats.hasActivity) BlockstreamClient.getUtxos(address, network) else emptyList()

                    ScannedAddress(chain, index, address, stats, utxos)
                }
            }.awaitAll()

            for (result in batch) {
                scanned += result
                if (result.stats.hasActivity) {
                    lastUsed = result.index
                    gap = 0
                } else {
                    gap++
                }
            }

            nextIndex = batchStart + batchSize
        }

        ChainResult(scanned, lastUsed + 1)
    }
}
