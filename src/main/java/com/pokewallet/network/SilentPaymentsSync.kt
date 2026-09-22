package com.pokewallet.network

import com.pokewallet.crypto.Network
import com.pokewallet.crypto.SilentPaymentsScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

    /** Nº máximo de blocos por stream gRPC. Longos streams de 300-500+
     *  blocos são justamente onde a conexão HTTP/2 morre em rede móvel;
     *  fatiar em janelas pequenas permite reconectar/persistir o progresso
     *  entre as janelas em vez de recomeçar um stream gigante. */
    const val SCAN_CHUNK_BLOCKS = 100L

    /** Pausa entre janelas de [SCAN_CHUNK_BLOCKS]. Não é um sleep cego:
     *  dá tempo pro oracle/proxy liberar a conexão anterior e mantém o
     *  scan num ritmo previsível (~100 blocos por minuto) em rescans longos.
     */
    const val SCAN_CHUNK_PAUSE_MS = 60_000L

    fun defaultStartHeight(oracleTipHeight: Long): Long = maxOf(0L, oracleTipHeight - DEFAULT_LOOKBACK_BLOCKS)

    /**
     * Confere que o "network" que o oracle reporta (GetInfo) bate com a
     * rede esperada — trava de segurança contra o host errado ficar
     * configurado pra rede errada (bug real encontrado ao vivo nesta
     * sessão: BlindBitOraclePrefs guardava o host customizado numa chave
     * ÚNICA compartilhada entre redes, então configurar em signet vazava
     * pro mainnet depois — já corrigido lá, mas esta checagem garante que
     * NENHUMA futura forma de host errado (erro de digitação, oracle
     * próprio mal configurado, etc.) passe batido escaneando a chain
     * ERRADA silenciosamente — o usuário só veria "nada encontrado" pra
     * sempre, sem entender por quê. [Network.TESTNET] aceita tanto
     * "signet" quanto "testnet" (esta wallet não distingue os dois, ver
     * doc de [BlindBitOraclePrefs]).
     */
    fun matchesExpectedNetwork(expected: Network, oracleReportedNetwork: String): Boolean {
        val reported = oracleReportedNetwork.trim().lowercase()
        return when (expected) {
            Network.MAINNET -> reported == "mainnet" || reported == "bitcoin" || reported == "main"
            Network.TESTNET -> reported == "signet" || reported == "testnet"
            Network.REGTEST -> reported == "regtest"
        }
    }

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
     *
     * A faixa é fatiada em janelas de [SCAN_CHUNK_BLOCKS], com pausa entre
     * elas. Cada stream gRPC cobre só uma janela — em rede móvel (troca
     * WiFi/dados, NAT de operadora derrubando conexão ociosa) um stream de
     * 300-500+ blocos morria no meio com "RPC transport failure
     * (HTTP status=200, grpc-status=null)" — erro real relatado em campo,
     * 2026-09-21. Dentro de cada janela, reconecta a partir do PRÓXIMO
     * bloco ainda não processado (nunca reprocessa nem pula um bloco:
     * [nextStart] só avança depois de [onBlockScanned] confirmar que aquele
     * bloco foi processado com sucesso) — até [MAX_STREAM_RETRIES]
     * tentativas com backoff crescente antes de desistir e propagar o erro
     * real.
     */
    const val MAX_STREAM_RETRIES = 5

    suspend fun scanRange(
        oracleBaseUrl: String,
        dataSource: ChainDataSource,
        network: Network,
        scanPrivateKey: ByteArray,
        spendPubKey: ByteArray,
        startHeight: Long,
        endHeight: Long,
        onBlockScanned: (height: Long, start: Long, end: Long) -> Unit = { _, _, _ -> },
        onChunkCompleted: (chunkUtxos: List<SilentPaymentsConfirmer.ConfirmedUtxo>, chunkTipHeight: Long) -> Unit = { _, _ -> }
    ): List<SilentPaymentsConfirmer.ConfirmedUtxo> {
        val confirmed = mutableListOf<SilentPaymentsConfirmer.ConfirmedUtxo>()
        var nextStart = startHeight

        while (nextStart <= endHeight) {
            val chunkEnd = minOf(nextStart + SCAN_CHUNK_BLOCKS - 1, endHeight)
            val chunkConfirmed = scanRangeChunk(
                oracleBaseUrl  = oracleBaseUrl,
                dataSource     = dataSource,
                network        = network,
                scanPrivateKey = scanPrivateKey,
                spendPubKey    = spendPubKey,
                startHeight    = nextStart,
                endHeight      = chunkEnd,
                fullRangeStart = startHeight,
                fullRangeEnd   = endHeight,
                onBlockScanned = onBlockScanned
            )
            confirmed += chunkConfirmed
            onChunkCompleted(chunkConfirmed, chunkEnd)
            nextStart = chunkEnd + 1

            if (nextStart <= endHeight) {
                delay(SCAN_CHUNK_PAUSE_MS)
            }
        }

        return confirmed
    }

    private suspend fun scanRangeChunk(
        oracleBaseUrl: String,
        dataSource: ChainDataSource,
        network: Network,
        scanPrivateKey: ByteArray,
        spendPubKey: ByteArray,
        startHeight: Long,
        endHeight: Long,
        fullRangeStart: Long,
        fullRangeEnd: Long,
        onBlockScanned: (height: Long, start: Long, end: Long) -> Unit
    ): List<SilentPaymentsConfirmer.ConfirmedUtxo> {
        val confirmed = mutableListOf<SilentPaymentsConfirmer.ConfirmedUtxo>()
        var nextStart = startHeight
        var attempt = 0

        while (nextStart <= endHeight) {
            var madeProgress = false
            try {
                BlindBitOracleClient.streamBlockScanDataShort(oracleBaseUrl, nextStart, endHeight).collect { block ->
                    val candidates = SilentPaymentsScanner.scanBlock(block, scanPrivateKey, spendPubKey)
                    // Confirma o bloco INTEIRO num temporário antes de anexar:
                    // se um confirm lançar no meio (ex.: getRawTx falhou),
                    // o temporário é descartado e o retry reprocessa o bloco
                    // sem duplicar os candidatos já confirmados antes.
                    val blockConfirmed = candidates.mapNotNull { c ->
                        SilentPaymentsConfirmer.confirm(c, dataSource, network)
                    }
                    confirmed += blockConfirmed
                    // A UI continua vendo o progresso da faixa COMPLETA, não
                    // da janela de 100 blocos — senão a porcentagem pularia
                    // de 100% pra 0% a cada novo chunk.
                    onBlockScanned(block.blockHeight, fullRangeStart, fullRangeEnd)
                    nextStart = block.blockHeight + 1
                    madeProgress = true
                    attempt = 0 // progresso real feito — reseta o contador de tentativas
                }
                // Stream terminou normalmente sem emitir NENHUM bloco — se
                // nextStart não avançou, re-entrar no while seria um loop
                // apertado contra o oracle. Encerra em vez de insistir.
                if (!madeProgress) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt > MAX_STREAM_RETRIES) throw e
                delay(1_000L * (1L shl (attempt - 1))) // 1s, 2s, 4s, 8s, 16s
            }
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
        onBlockScanned: (height: Long, start: Long, end: Long) -> Unit = { _, _, _ -> },
        onChunkCompleted: (chunkUtxos: List<SilentPaymentsConfirmer.ConfirmedUtxo>, chunkTipHeight: Long) -> Unit = { _, _ -> }
    ): SyncResult {
        val info = BlindBitOracleClient.getInfo(oracleBaseUrl)
        require(matchesExpectedNetwork(network, info.network)) {
            "O oracle configurado ($oracleBaseUrl) está servindo dados de \"${info.network}\", mas esta carteira é " +
                "${network.name} — provável host de oracle errado pra esta rede (confira em Configurar Oracle na " +
                "Mochila). Scan cancelado por segurança: escanear a chain errada nunca encontraria o pagamento " +
                "real, silenciosamente."
        }
        val tip = info.height
        val start = resolveStartHeight(previousScanTipHeight, birthHeight, tip)
        if (start > tip) return SyncResult(emptyList(), previousScanTipHeight)

        val confirmed = scanRange(
            oracleBaseUrl     = oracleBaseUrl,
            dataSource        = dataSource,
            network           = network,
            scanPrivateKey    = scanPrivateKey,
            spendPubKey       = spendPubKey,
            startHeight       = start,
            endHeight         = tip,
            onBlockScanned    = onBlockScanned,
            onChunkCompleted  = onChunkCompleted
        )
        return SyncResult(confirmed, tip)
    }
}
