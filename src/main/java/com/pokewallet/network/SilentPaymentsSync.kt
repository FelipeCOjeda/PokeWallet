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
     * Quantos blocos voltar do tip pro PRIMEIRO scan de uma carteira nova —
     * decisão explícita do usuário: cada bloco custa uma leva de
     * multiplicações de ponto de curva elíptica (uma por transação
     * elegível), pesado o bastante num celular sem aceleração de hardware
     * que 2000 blocos (valor original, ~2 semanas em mainnet) levou minutos
     * demais num teste real — 100 blocos (~16h em mainnet, mais rápido em
     * signet) é um padrão bem mais prático pro primeiro scan. Pagamentos SP
     * mais antigos que isso ficam invisíveis até um rescan completo manual
     * (ainda não implementado) ou até apontar spScanTipHeight pra uma
     * altura mais antiga manualmente.
     */
    const val DEFAULT_LOOKBACK_BLOCKS = 100L

    fun defaultStartHeight(oracleTipHeight: Long): Long = maxOf(0L, oracleTipHeight - DEFAULT_LOOKBACK_BLOCKS)

    /** Margem de segurança pra trás da altura de nascimento — cobre
     *  reorg/latência entre "app leu a altura atual" e "bloco que criou a
     *  carteira foi minerado", não uma estimativa de precisão. */
    const val BIRTH_HEIGHT_MARGIN_BLOCKS = 2L

    /**
     * Decide de qual altura começar um scan: continua de onde parou
     * ([previousScanTipHeight]+1) quando há um scan anterior; senão prefere
     * [birthHeight] - [BIRTH_HEIGHT_MARGIN_BLOCKS] quando conhecida (ver
     * WalletData.birthHeight); cai pro lookback fixo ([defaultStartHeight])
     * só quando nem um nem outro está disponível.
     */
    fun resolveStartHeight(previousScanTipHeight: Long, birthHeight: Long?, oracleTipHeight: Long): Long = when {
        previousScanTipHeight > 0L -> previousScanTipHeight + 1
        birthHeight != null -> maxOf(0L, birthHeight - BIRTH_HEIGHT_MARGIN_BLOCKS)
        else -> defaultStartHeight(oracleTipHeight)
    }

    data class SyncResult(
        val confirmedUtxos: List<SilentPaymentsConfirmer.ConfirmedUtxo>,
        val newScanTipHeight: Long
    )

    /**
     * Escaneia [startHeight]..[endHeight] (inclusive dos dois lados).
     * [onBlockScanned], se dado, é chamado depois de processar CADA bloco
     * (altura processada, altura inicial, altura final) — únicas provas de
     * vida durante um scan longo (2000 blocos = ~2000 multiplicações de
     * ponto de curva elíptica, pode levar minutos num celular sem
     * aceleração de hardware; sem isso a UI não tem como distinguir "lento
     * mas funcionando" de "travado").
     */
    suspend fun scanRange(
        oracleBaseUrl: String,
        dataSource: ChainDataSource,
        network: Network,
        scanPrivateKey: ByteArray,
        spendPubKey: ByteArray,
        startHeight: Long,
        endHeight: Long,
        onBlockScanned: (height: Long, start: Long, end: Long) -> Unit = { _, _, _ -> }
    ): List<SilentPaymentsConfirmer.ConfirmedUtxo> {
        val confirmed = mutableListOf<SilentPaymentsConfirmer.ConfirmedUtxo>()
        BlindBitOracleClient.streamBlockScanDataShort(oracleBaseUrl, startHeight, endHeight).collect { block ->
            val candidates = SilentPaymentsScanner.scanBlock(block, scanPrivateKey, spendPubKey)
            candidates.forEach { c ->
                SilentPaymentsConfirmer.confirm(c, dataSource, network)?.let { confirmed += it }
            }
            onBlockScanned(block.blockHeight, startHeight, endHeight)
        }
        return confirmed
    }

    /**
     * Fluxo completo pra uma carteira: descobre o tip do oracle, decide o
     * range e escaneia+confirma. No PRIMEIRO scan ([previousScanTipHeight]
     * <= 0), prefere [birthHeight] - [BIRTH_HEIGHT_MARGIN_BLOCKS] quando
     * conhecida (carteira criada nesta sessão, ver WalletData.birthHeight)
     * — muito mais preciso e rápido que [defaultStartHeight] (lookback
     * fixo), que só serve de fallback quando [birthHeight] é null (carteira
     * restaurada/importada/antiga, sem como saber a altura real de
     * nascimento). Devolve lista vazia + a mesma altura anterior se já
     * estiver em dia (nada de novo desde o último sync).
     */
    suspend fun sync(
        oracleBaseUrl: String,
        dataSource: ChainDataSource,
        network: Network,
        scanPrivateKey: ByteArray,
        spendPubKey: ByteArray,
        previousScanTipHeight: Long,
        birthHeight: Long? = null,
        onBlockScanned: (height: Long, start: Long, end: Long) -> Unit = { _, _, _ -> }
    ): SyncResult {
        val tip = BlindBitOracleClient.getInfo(oracleBaseUrl).height
        val start = resolveStartHeight(previousScanTipHeight, birthHeight, tip)
        if (start > tip) return SyncResult(emptyList(), previousScanTipHeight)

        val confirmed = scanRange(oracleBaseUrl, dataSource, network, scanPrivateKey, spendPubKey, start, tip, onBlockScanned)
        return SyncResult(confirmed, tip)
    }
}
