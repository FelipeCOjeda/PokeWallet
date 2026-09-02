package com.pokewallet.network

import com.pokewallet.crypto.Network
import com.pokewallet.crypto.SilentPaymentsScanner
import kotlinx.coroutines.flow.collect

/**
 * Liga [BlindBitOracleClient] + [SilentPaymentsScanner] +
 * [SilentPaymentsConfirmer] — escaneia uma faixa de altura e devolve só os
 * UTXOs SP já CONFIRMADOS contra uma fonte própria. NÃO persiste — quem
 * chama decide (ver `WalletStorage.addSilentPaymentUtxos`).
 */
object SilentPaymentsSync {

    /**
     * Quantos blocos voltar do tip pro PRIMEIRO scan de uma carteira nova
     * (~2 semanas em mainnet) — decisão explícita do usuário: meio-termo
     * entre simplicidade (sem pedir altura manual) e não perder pagamentos
     * recentes. Pagamentos SP mais antigos que isso ficam invisíveis até
     * um rescan completo manual (ainda não implementado).
     */
    const val DEFAULT_LOOKBACK_BLOCKS = 2_000L

    fun defaultStartHeight(oracleTipHeight: Long): Long = maxOf(0L, oracleTipHeight - DEFAULT_LOOKBACK_BLOCKS)

    data class SyncResult(
        val confirmedUtxos: List<SilentPaymentsConfirmer.ConfirmedUtxo>,
        val newScanTipHeight: Long
    )

    /** Escaneia [startHeight]..[endHeight] (inclusive dos dois lados). */
    suspend fun scanRange(
        oracleBaseUrl: String,
        dataSource: ChainDataSource,
        network: Network,
        scanPrivateKey: ByteArray,
        spendPubKey: ByteArray,
        startHeight: Long,
        endHeight: Long
    ): List<SilentPaymentsConfirmer.ConfirmedUtxo> {
        val confirmed = mutableListOf<SilentPaymentsConfirmer.ConfirmedUtxo>()
        BlindBitOracleClient.streamBlockScanDataShort(oracleBaseUrl, startHeight, endHeight).collect { block ->
            val candidates = SilentPaymentsScanner.scanBlock(block, scanPrivateKey, spendPubKey)
            candidates.forEach { c ->
                SilentPaymentsConfirmer.confirm(c, dataSource, network)?.let { confirmed += it }
            }
        }
        return confirmed
    }

    /**
     * Fluxo completo pra uma carteira: descobre o tip do oracle, decide o
     * range ([previousScanTipHeight]+1..tip, ou [defaultStartHeight] se for
     * o primeiro scan — [previousScanTipHeight] <= 0), escaneia+confirma.
     * Devolve lista vazia + a mesma altura anterior se já estiver em dia
     * (nada de novo desde o último sync).
     */
    suspend fun sync(
        oracleBaseUrl: String,
        dataSource: ChainDataSource,
        network: Network,
        scanPrivateKey: ByteArray,
        spendPubKey: ByteArray,
        previousScanTipHeight: Long
    ): SyncResult {
        val tip = BlindBitOracleClient.getInfo(oracleBaseUrl).height
        val start = if (previousScanTipHeight <= 0L) defaultStartHeight(tip) else previousScanTipHeight + 1
        if (start > tip) return SyncResult(emptyList(), previousScanTipHeight)

        val confirmed = scanRange(oracleBaseUrl, dataSource, network, scanPrivateKey, spendPubKey, start, tip)
        return SyncResult(confirmed, tip)
    }
}
