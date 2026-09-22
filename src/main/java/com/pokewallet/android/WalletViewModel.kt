package com.pokewallet.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokewallet.crypto.*
import com.pokewallet.lightning.supportsLightning
import com.pokewallet.network.BalanceCrossChecker
import com.pokewallet.network.BlockstreamClient
import com.pokewallet.network.ChainDataSource
import com.pokewallet.network.FailoverChainDataSource
import com.pokewallet.network.FeeEstimates
import com.pokewallet.network.RemoteUtxo
import com.pokewallet.network.SilentPaymentsConfirmer
import com.pokewallet.network.SilentPaymentsSync
import com.pokewallet.network.TorBlockstreamDataSource
import com.pokewallet.network.UtxoValueVerifier
import com.pokewallet.network.WalletScanner
import com.pokewallet.nostr.GeoRelayDirectory
import com.pokewallet.nostr.NostrEvent
import com.pokewallet.nostr.NostrKeys
import com.pokewallet.nostr.NostrRelayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Date

sealed class WalletState {
    object NoWallet : WalletState()
    object Creating : WalletState()
    data class Created(
        val mnemonic: String,
        val passphrase: String,
        val passphraseMode: PassphraseMode
    ) : WalletState()
    object Loading : WalletState()
    data class Loaded(
        val fingerprint: String,
        val walletName: String,
        val displayName: String,
        val network: Network,
        val balanceSats: Long?,
        val pendingSats: Long?,
        val utxoCount: Int?,
        val isScanning: Boolean,
        val scanStatus: String?,
        val lastScanTime: Date?,
        /** Carteira importada só por xpub (Fase C) — sem seed neste
         *  dispositivo, não pode assinar/enviar localmente. */
        val isWatchOnly: Boolean = false,
        /** Mensagem do último scan que FALHOU (rede, timeout, etc.) — antes
         *  disso doScan() engolia qualquer exceção em silêncio, deixando o
         *  saldo parado no valor antigo sem nenhum aviso visível. Limpo
         *  (null) assim que um scan tiver sucesso de novo. */
        val lastScanError: String? = null,
        /** Não-nulo só quando o cross-check opcional (Mochila → node
         *  Electrum → "Cruzar saldo com API pública") está ligado E achou
         *  divergência — ver BalanceCrossChecker. */
        val balanceCrossCheckWarning: String? = null
    ) : WalletState()
    data class Error(val message: String) : WalletState()
}

/**
 * Conexão com a Breez SDK - Spark (Lightning) — opt-in e isolada do resto
 * do app: nunca conecta sozinha ao carregar a carteira, só quando o
 * usuário toca "Ativar Lightning" na Mochila (ver [WalletFragment]).
 */
sealed class LightningState {
    /** Watch-only (sem seed neste aparelho) ou rede sem suporte da Spark
     *  (só MAINNET/REGTEST, ver [com.pokewallet.lightning.supportsLightning]). */
    object Unavailable : LightningState()
    object Disconnected : LightningState()
    object Connecting : LightningState()
    data class Connected(val balanceSats: Long) : LightningState()
    data class Error(val message: String) : LightningState()
}

sealed class RestoreState {
    object Idle      : RestoreState()
    object Restoring : RestoreState()
    object Success   : RestoreState()
    data class Error(val message: String) : RestoreState()
}

sealed class WatchOnlyImportState {
    object Idle      : WatchOnlyImportState()
    object Importing : WatchOnlyImportState()
    object Success   : WatchOnlyImportState()
    data class Error(val message: String) : WatchOnlyImportState()
    /** A xpub importada é da MESMA carteira (mesmo fingerprint) que já existe
     *  NESTE aparelho com a seed — pede confirmação explícita antes de
     *  esquecer a versão com chave e trocar por esta watch-only. */
    data class ConflictWithKeyedWallet(val fingerprint: String) : WatchOnlyImportState()
}

/** PSBT não-assinado pronto pra sair como QR do lado watch-only (Fase C4). */
data class AirGappedPsbt(
    val psbtBase64: String,
    val expectedTxid: String,
    val network: Network
)

/** Lado watch-only: montar o PSBT não-assinado. */
sealed class AirGappedSendState {
    object Idle     : AirGappedSendState()
    object Building : AirGappedSendState()
    data class Ready(val psbt: AirGappedPsbt) : AirGappedSendState()
    data class Error(val message: String) : AirGappedSendState()
}

/** Lado watch-only: recebeu a tx assinada de volta, verifica e transmite (Fase C5). */
sealed class AirGappedBroadcastState {
    object Idle       : AirGappedBroadcastState()
    object Verifying  : AirGappedBroadcastState()
    data class Success(val txid: String) : AirGappedBroadcastState()
    data class Error(val message: String) : AirGappedBroadcastState()
}

/** Lado signer: assina o PSBT escaneado com a seed local. */
sealed class AirGappedSignState {
    object Idle    : AirGappedSignState()
    object Signing : AirGappedSignState()
    data class Success(val rawTxHex: String, val txid: String) : AirGappedSignState()
    data class Error(val message: String) : AirGappedSignState()
}

data class WalletTx(
    val txid: String,
    val netSats: Long,
    val confirmed: Boolean,
    val blockTime: Long?
)

/** Uma linha da lista de endereços derivados (Fase A). */
data class AddressRow(
    val chain: Int,           // 0 = externo (recebimento), 1 = interno (troco)
    val index: Int,
    val address: String,
    val balanceSats: Long,
    val used: Boolean
)

/** Um UTXO individual, com o endereço/índice HD que o controla (Fase B1). */
data class UtxoRow(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val confirmed: Boolean,
    val address: String,
    val chain: Int,
    val index: Int,
    val frozen: Boolean
) {
    val key: String get() = "$txid:$vout"
}

sealed class SendState {
    object Idle : SendState()
    object Sending : SendState()
    /** Só usado no modo BitChat: assinado, publicando o evento Nostr nos relays. */
    object PublishingToRelays : SendState()
    /** Só usado no modo BitChat: publicado em pelo menos um relay, esperando o bot confirmar. */
    data class AwaitingRelayConfirmation(val txid: String) : SendState()
    data class Success(
        val txid: String,
        val confirmedByRelay: Boolean,
        val relayReplyText: String? = null
    ) : SendState()
    data class Error(val message: String) : SendState()
}

/** Caminho de broadcast escolhido pelo usuário na hora de enviar. */
sealed class SendMode {
    object Internet : SendMode()
    /** Broadcast via Blockstream/mempool.space roteado por um proxy SOCKS5
     *  local (Orbot) — ver [TorPrefs]. Exige o Orbot instalado e rodando. */
    object Tor : SendMode()
    object BitChat : SendMode()
}

private data class NostrSendResult(val txid: String, val confirmed: Boolean, val replyText: String?)

/**
 * Geohash do canal BitChat onde o bitchat-broadcaster escuta.
 * Precisa bater com o GEOHASH_CHANNEL configurado no bot
 * (ver /home/felipe/Bots/bitchat-broadcaster/.env — default "6g").
 */
private const val BITCHAT_GEOHASH = "6g"

/**
 * Pubkey Nostr fixa do bitchat-broadcaster (NOSTR_PRIVATE_KEY configurada
 * no .env dele, não é chave efêmera — ver src/broadcaster.js). Usada pra
 * só aceitar como "tx confirmada" uma resposta assinada por ESSA
 * identidade — sem isso, qualquer participante do canal público podia
 * forjar uma confirmação (o txid citado nela é público, extraído do
 * próprio `!broadcast <hex>` que a wallet acabou de publicar). Se o bot
 * for redeployado com uma chave nova, essa constante precisa acompanhar —
 * até lá, o pior caso é a wallet nunca reconhecer a confirmação (o envio
 * ainda funciona, só fica marcado como "publicado, sem confirmação do
 * relay" em vez de confirmado).
 */
private const val BITCHAT_BROADCASTER_PUBKEY_HEX =
    "ad8224492887a4b66795d0a8026a201226aeae67548631586d7a83dd40bf2707"

/** Tolerância do cross-check de saldo (NodePrefs.isCrossCheckEnabled) —
 *  evita falso positivo por divergência momentânea de mempool entre a
 *  fonte configurada e a API pública consultadas com segundos de diferença. */
private const val DUST_LIMIT_CROSS_CHECK_SATS = 1_000L

/**
 * Intervalo base do autoScanJob. Um scan típico faz ~40+ requests HTTP
 * sequenciais só de stats (gap limit 20 × 2 chains), mais UTXOs/histórico
 * dos endereços ativos, mais 1 de fee-estimates — tudo contra o MESMO host
 * (blockstream.info/mempool.space). A API pública do Blockstream permite
 * só 700 requests/hora por IP; com o intervalo antigo de 60s (até 60
 * scans/hora) isso estourava o limite bem rápido e a API passava a
 * responder 429 (erro real visto em campo — ver DOSCAN_RATE_LIMIT_BACKOFF_MS
 * abaixo pro tratamento). 4 min = no máximo 15 scans/hora, ~15×41≈615
 * requests/hora só do auto-scan — folga pra fee-estimates extra e um
 * refresh manual ocasional sem estourar de novo.
 */
private const val AUTO_SCAN_BASE_INTERVAL_MS = 240_000L

/**
 * Backoff aplicado ao PRÓXIMO ciclo do autoScanJob depois de um 429
 * (rate limit) — dobra a cada erro consecutivo (240s → 480s → 960s...),
 * até um teto, em vez de insistir no mesmo intervalo de 4 min e levar
 * outro 429 quase certo (a API já sinalizou que está bloqueando este IP
 * por enquanto). Reseta pro intervalo base assim que um scan tiver
 * sucesso de novo.
 */
private const val AUTO_SCAN_MAX_BACKOFF_MS = 20 * 60_000L // 20 min

/**
 * Intervalo do sync automático de Silent Payments. Usa a mesma cadência do
 * auto-scan on-chain: num cenário normal, cada ciclo pega poucos blocos
 * novos e o trabalho é incremental (spScanTipHeight). Se o oracle ou a
 * confirmação falharem, o backoff próprio de SP entra em ação
 * ([SP_AUTO_SYNC_MAX_BACKOFF_MS]).
 */
private const val SP_AUTO_SYNC_INTERVAL_MS = AUTO_SCAN_BASE_INTERVAL_MS

/** Espera máxima entre tentativas do sync automático de Silent Payments. */
private const val SP_AUTO_SYNC_MAX_BACKOFF_MS = 30 * 60_000L

/** Timeout de cada execução automática — um pouco menor que o manual pra
 *  não segurar o Mutex de SP por 15 minutos inteiros. */
private const val SP_AUTO_SYNC_TIMEOUT_MS = 10 * 60_000L

/** Primeira execução roda alguns segundos após carregar a carteira. */
private const val SP_AUTO_SYNC_FIRST_DELAY_MS = 5_000L

/**
 * Por quanto tempo o resultado do último scan (doScan()) é reaproveitado
 * por buildSignedTx() em vez de disparar um scan completo novo. 1.5x o
 * intervalo base do autoScanJob — cobre o caso comum (enviar logo após a
 * varredura periódica) sem arriscar UTXO desatualizado por muito tempo.
 */
private const val SCAN_CACHE_TTL_MS = (AUTO_SCAN_BASE_INTERVAL_MS * 1.5).toLong()

/** Extrai o nome do Pokémon de uma passphrase no formato "pokemon:N:Nome". */
private val POKEMON_PASSPHRASE_REGEX = Regex("^pokemon:\\d+:(.+)$")

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val _walletState = MutableStateFlow<WalletState>(WalletState.Loading)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _priceState = MutableStateFlow<BlockstreamClient.BtcPrices?>(null)
    val priceState: StateFlow<BlockstreamClient.BtcPrices?> = _priceState.asStateFlow()

    private val _feeState = MutableStateFlow<FeeEstimates?>(null)
    val feeState: StateFlow<FeeEstimates?> = _feeState.asStateFlow()

    private val _pendingTxEvent = MutableSharedFlow<Long>(replay = 0)
    val pendingTxEvent: SharedFlow<Long> = _pendingTxEvent.asSharedFlow()

    private val _txHistory = MutableStateFlow<List<WalletTx>>(emptyList())
    val txHistory: StateFlow<List<WalletTx>> = _txHistory.asStateFlow()

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private val _watchOnlyImportState = MutableStateFlow<WatchOnlyImportState>(WatchOnlyImportState.Idle)
    val watchOnlyImportState: StateFlow<WatchOnlyImportState> = _watchOnlyImportState.asStateFlow()

    private val _airGappedSendState = MutableStateFlow<AirGappedSendState>(AirGappedSendState.Idle)
    val airGappedSendState: StateFlow<AirGappedSendState> = _airGappedSendState.asStateFlow()

    private val _airGappedBroadcastState = MutableStateFlow<AirGappedBroadcastState>(AirGappedBroadcastState.Idle)
    val airGappedBroadcastState: StateFlow<AirGappedBroadcastState> = _airGappedBroadcastState.asStateFlow()

    private val _airGappedSignState = MutableStateFlow<AirGappedSignState>(AirGappedSignState.Idle)
    val airGappedSignState: StateFlow<AirGappedSignState> = _airGappedSignState.asStateFlow()

    private val _lightningState = MutableStateFlow<LightningState>(LightningState.Disconnected)
    val lightningState: StateFlow<LightningState> = _lightningState.asStateFlow()

    /** true quando a ÚLTIMA sincronização de Silent Payments recorreu ao
     *  pool de Electrum públicos (fallback) — exposto no status da Mochila
     *  pra avisar que o usuário falou com terceiro naquela consulta. */
    @Volatile private var lastSpSyncUsedFallback = false

    /** Job do sync automático de Silent Payments em segundo plano. */
    private var spAutoSyncJob: Job? = null

    /** true enquanto uma execução do sync automático está rodando. */
    @Volatile private var spAutoSyncRunning = false

    /** Nº de falhas consecutivas do sync automático — controla o backoff. */
    private var spAutoSyncConsecutiveFailures = 0

    /** Timestamp da última execução automática bem-sucedida. */
    @Volatile private var lastSpAutoSyncTimeMs: Long? = null

    /** Erro da última tentativa automática (ou null quando tudo bem). */
    @Volatile private var lastSpAutoSyncError: String? = null

    /** Não-null só enquanto conectado — [resetPerWalletCaches] garante que
     *  nunca sobrevive a uma troca/esquecimento de carteira (senão um envio
     *  Lightning depois de trocar de carteira sairia da carteira ERRADA). */
    private var lightningWallet: com.pokewallet.lightning.LightningWallet? = null
    private var lightningEventsJob: Job? = null

    private var lastKnownPendingSats: Long = 0L
    private var autoScanJob: Job? = null

    // Cache do último scan bem-sucedido (doScan(), que já roda a cada 60s via
    // autoScanJob) — reusado por buildSignedTx() pra evitar repetir um scan
    // completo (5-15s, dezenas de requests HTTP) que acabou de rodar. Só é
    // reaproveitado se ainda estiver "fresco" (ver SCAN_CACHE_TTL_MS) e for da
    // mesma rede; caso contrário buildSignedTx() força um scan novo.
    private var lastScanResult: com.pokewallet.network.WalletScanner.ScanResult? = null
    private var lastScanResultAtMs: Long = 0L

    // Serializa toda operação que troca a identidade da carteira ativa
    // (criar, restaurar, trocar, esquecer) contra qualquer operação em
    // andamento que dependa de "qual carteira está ativa agora" (scan,
    // montar+assinar uma tx) — sem isso, uma troca no meio de um
    // carregar→mutar→salvar pode gravar dado da carteira A no wallet.json
    // da carteira B.
    private val walletSwitchMutex = Mutex()

    /** Serializa execuções automática e manual do sync de Silent Payments,
     *  pra evitar dois scans SP simultâneos quando o usuário toca em
     *  "Sincronizar agora" enquanto o job de fundo já está rodando. */
    private val spSyncMutex = Mutex()

    init {
        checkWallet()
    }

    private fun checkWallet() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { migrateAndResolveActiveWallet(context) }
            } catch (e: Exception) {
                _walletState.value = WalletState.Error(
                    "Falha ao migrar pro formato multi-wallet: ${e.message ?: "erro desconhecido"}"
                )
                return@launch
            }

            if (!WalletStorage.exists()) {
                _walletState.value = WalletState.NoWallet
                return@launch
            }

            try {
                val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                if (wallet.mnemonicVerified) {
                    loadWalletAndStartScan()
                } else {
                    // Wallet foi criada mas o usuário nunca completou o quiz de
                    // confirmação (app fechou/matou o processo antes) — retoma o
                    // fluxo de verificação em vez de liberar acesso à wallet.
                    // Só alcançável por carteira com seed: watch-only sempre grava
                    // mnemonicVerified=true na criação (não há quiz a fazer).
                    val mode = PassphraseMode.fromPersisted(
                        wallet.raw.optString("passphraseMode"),
                        wallet.passphrase!!
                    )
                    _walletState.value = WalletState.Created(
                        mnemonic       = wallet.mnemonic!!.joinToString(" "),
                        passphrase     = wallet.passphrase!!,
                        passphraseMode = mode
                    )
                }
            } catch (e: Exception) {
                _walletState.value = WalletState.Error(humanizeError(e))
            }
        }
    }

    /**
     * Roda a cada início de processo (idempotente, seguro de repetir):
     * 1) migra o wallet.json antigo (formato plano, uma carteira só) pro
     *    novo layout wallets/<fingerprint>/, via renameTo() atômico — só
     *    considera concluído se o retorno for true; nunca apaga o arquivo
     *    antigo manualmente, só o próprio renameTo() o remove da origem.
     * 2) recupera um wallets/_pending/ órfão (app matado no meio de uma
     *    criação/restauração anterior).
     * 3) resolve qual carteira fica ativa (registro, ou auto-seleciona se
     *    houver exatamente uma carteira conhecida e nenhuma ativa marcada).
     * Sempre deixa WalletStorage.filesDir apontando pra pasta certa —
     * ou pra app.filesDir (sem wallet.json) se não há nenhuma carteira.
     */
    private fun migrateAndResolveActiveWallet(context: Application) {
        val root = WalletRegistry.walletsRoot(context.filesDir)
        root.mkdirs()

        // 1) Layout antigo: filesDir/wallet.json direto (uma carteira só)
        val legacyFile = File(context.filesDir, "wallet.json")
        if (legacyFile.exists()) {
            WalletStorage.filesDir = context.filesDir
            val fingerprint = runCatching { WalletStorage.load().fingerprint }.getOrNull()
            if (fingerprint != null) {
                val target = WalletRegistry.walletDir(context.filesDir, fingerprint)
                if (!target.exists()) {
                    target.mkdirs()
                    val moved = legacyFile.renameTo(File(target, "wallet.json"))
                    if (moved) {
                        WalletRegistry.setActiveWalletId(context, fingerprint)
                    }
                }
            }
        }

        // 2) _pending órfão de uma criação/restauração interrompida
        val pending = WalletRegistry.pendingDir(context.filesDir)
        if (File(pending, "wallet.json").exists()) {
            WalletStorage.filesDir = pending
            val fingerprint = runCatching { WalletStorage.load().fingerprint }.getOrNull()
            val target = fingerprint?.let { WalletRegistry.walletDir(context.filesDir, it) }
            when {
                fingerprint == null -> pending.deleteRecursively() // corrompido, não recuperável
                target!!.exists()   -> pending.deleteRecursively() // colisão, descarta o pending órfão
                else                -> pending.renameTo(target)
            }
        }

        // 3) Resolve a carteira ativa
        val known = WalletRegistry.listKnownWalletIds(context.filesDir)
        var activeId = WalletRegistry.activeWalletId(context)
        if (activeId == null || activeId !in known) {
            activeId = known.singleOrNull()
            if (activeId != null) WalletRegistry.setActiveWalletId(context, activeId)
        }

        WalletStorage.filesDir = if (activeId != null)
            WalletRegistry.walletDir(context.filesDir, activeId)
        else
            context.filesDir
    }

    private fun loadWalletAndStartScan() {
        viewModelScope.launch {
            try {
                // Estado (balanço, histórico, taxas, scan-cache) é sempre por
                // carteira — limpa antes de carregar, senão dado da carteira
                // anterior aparece na tela até o primeiro scan da nova terminar.
                resetPerWalletCaches()
                val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                val displayName = WalletRegistry.displayNameOrFallback(getApplication(), wallet.fingerprint)
                // Memória local do último saldo conhecido (ver doc em
                // WalletData.cachedBalanceSats) — pinta a tela com isso
                // IMEDIATO em vez de null/loading, sem esperar a resposta
                // de rede. doScan() (chamado logo abaixo por startAutoScan())
                // continua rodando por trás pra atualizar; isScanning=false
                // aqui só reflete que essa pintura inicial não é, ela
                // mesma, um scan em andamento.
                _walletState.value = WalletState.Loaded(
                    fingerprint  = wallet.fingerprint,
                    walletName   = wallet.walletName,
                    displayName  = displayName,
                    network      = wallet.network,
                    balanceSats  = wallet.cachedBalanceSats,
                    pendingSats  = wallet.cachedPendingSats?.takeIf { it != 0L },
                    utxoCount    = wallet.cachedUtxoCount,
                    isScanning   = false,
                    scanStatus   = null,
                    lastScanTime = wallet.cachedScanTimeMs?.let { Date(it) },
                    isWatchOnly  = wallet.isWatchOnly
                )
                startAutoScan()
                startAutoSilentPaymentsSync(enabled = wallet.spendType == SpendType.BIP86)
            } catch (e: Exception) {
                _walletState.value = WalletState.Error(humanizeError(e))
            }
        }
    }

    /** Reseta todo cache/estado específico da carteira que estava ativa antes —
     *  chamado sempre que uma carteira (diferente ou recém-criada) vai virar
     *  a ativa, pra não vazar saldo/histórico/taxa da anterior na tela. */
    private fun resetPerWalletCaches() {
        autoScanJob?.cancel()
        autoScanJob = null
        spAutoSyncJob?.cancel()
        spAutoSyncJob = null
        spAutoSyncRunning = false
        spAutoSyncConsecutiveFailures = 0
        lastSpAutoSyncTimeMs = null
        lastSpAutoSyncError = null
        lastSpSyncUsedFallback = false
        lastScanResult = null
        lastScanResultAtMs = 0L
        lastKnownPendingSats = 0L
        _txHistory.value = emptyList()
        _feeState.value = null
        disconnectLightningIfConnected()
    }

    /** Desconecta a Breez SDK - Spark da carteira que estava ativa, se
     *  houver — chamado sempre por [resetPerWalletCaches] (troca/esquece
     *  carteira, e também no load inicial do app, onde é um no-op). O
     *  disconnect() em si é fire-and-forget: não vale segurar quem chamou
     *  esperando a SDK fechar arquivo/DB, e mesmo que [forgetWallet] apague
     *  o diretório antes desse disconnect terminar, apagar um arquivo ainda
     *  aberto pelo próprio processo não dá erro no Linux/Android (o inode
     *  só é liberado quando o último handle fecha). */
    private fun disconnectLightningIfConnected() {
        lightningEventsJob?.cancel()
        lightningEventsJob = null
        val toDisconnect = lightningWallet
        lightningWallet = null
        _lightningState.value = LightningState.Disconnected
        if (toDisconnect != null) {
            viewModelScope.launch {
                try { toDisconnect.disconnect() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Conecta a Breez SDK - Spark usando o MESMO seed BIP39 desta carteira
     * (ver [com.pokewallet.lightning.LightningWallet.Companion.connect]) —
     * chamado só sob demanda (botão "Ativar Lightning" na Mochila), nunca
     * automaticamente ao carregar a carteira.
     */
    fun connectLightning() {
        val current = _walletState.value as? WalletState.Loaded ?: return
        if (lightningWallet != null) return // já conectado, ignora segundo toque

        if (current.isWatchOnly || !current.network.supportsLightning) {
            _lightningState.value = LightningState.Unavailable
            return
        }

        _lightningState.value = LightningState.Connecting
        viewModelScope.launch {
            try {
                val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                val connected = com.pokewallet.lightning.LightningWallet.connect(
                    context    = getApplication(),
                    walletId   = wallet.fingerprint,
                    mnemonic   = wallet.mnemonic!!,
                    passphrase = wallet.passphrase!!,
                    network    = wallet.network,
                )
                // Troca de carteira pode ter acontecido enquanto o connect()
                // (rede + I/O) estava em voo — descarta o resultado em vez de
                // deixar a conexão da carteira ANTIGA valer pra ativa nova.
                if ((_walletState.value as? WalletState.Loaded)?.fingerprint != current.fingerprint) {
                    connected.disconnect()
                    return@launch
                }
                lightningWallet = connected
                _lightningState.value = LightningState.Connected(connected.balanceSats())
                lightningEventsJob = viewModelScope.launch {
                    // Não filtra por tipo de evento — qualquer evento do SDK
                    // pode ter mexido no saldo, e reconsultar getInfo() é
                    // barato comparado ao resto do fluxo (rede/criptografia).
                    connected.events().collect {
                        try {
                            _lightningState.value = LightningState.Connected(connected.balanceSats(ensureSynced = false))
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                lightningWallet = null
                _lightningState.value = LightningState.Error(humanizeError(e))
            }
        }
    }

    suspend fun lightningReceiveBolt11(amountSats: Long?, description: String): String =
        lightningWallet?.receiveBolt11(amountSats, description)
            ?: throw IllegalStateException("Lightning não conectado")

    suspend fun lightningReceiveSparkAddress(): String =
        lightningWallet?.receiveSparkAddress()
            ?: throw IllegalStateException("Lightning não conectado")

    /** Endereço de depósito on-chain (peg-in) — sempre o mesmo, ver
     *  [com.pokewallet.lightning.LightningWallet.receiveOnchainDepositAddress]. */
    suspend fun lightningReceiveOnchainDepositAddress(): String =
        lightningWallet?.receiveOnchainDepositAddress()
            ?: throw IllegalStateException("Lightning não conectado")

    /**
     * Endereço on-chain FIXO desta carteira (sempre índice 0 da cadeia
     * externa) — usado só pelo saque Lightning→on-chain (peg-out), NUNCA
     * pelo botão "Receber" normal (que sempre avança o índice via
     * [WalletStorage.reserveNextExternalIndex]). Fixo de propósito: um
     * saque pequeno pra um endereço NOVO a cada vez arriscaria empurrar o
     * índice externo adiante rápido demais sem o usuário notar — o scanner
     * varre um gap limit fixo (ver WalletScanner), então um índice usado
     * além dele fica invisível pro saldo até um rescan completo manual.
     * Índice 0 é sempre coberto por qualquer gap limit razoável.
     */
    suspend fun getFixedPegOnchainAddress(): String? = walletSwitchMutex.withLock {
        try {
            val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
            if (wallet.isWatchOnly) return@withLock null
            val seed = withContext(Dispatchers.IO) {
                SeedDerivation.fromMnemonic(wallet.mnemonic!!, wallet.passphrase!!)
            }
            try {
                withContext(Dispatchers.IO) {
                    ReceiveAddressService.addressAt(
                        seed      = seed,
                        spendType = wallet.spendType,
                        network   = wallet.network,
                        index     = 0
                    )
                }
            } finally {
                seed.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun lightningPrepareSend(raw: String, amountSatsOverride: Long?): com.pokewallet.lightning.PreparedLightningPayment =
        lightningWallet?.prepareSend(raw, amountSatsOverride)
            ?: throw IllegalStateException("Lightning não conectado")

    /** Prepara o saque de TODO o saldo Lightning pro endereço on-chain FIXO
     *  desta carteira (peg-out "sacar tudo") — ver
     *  [com.pokewallet.lightning.LightningWallet.prepareSweepToOnchain]. */
    suspend fun lightningPrepareSweepToOnchain(): com.pokewallet.lightning.PreparedLightningPayment {
        val active = lightningWallet ?: throw IllegalStateException("Lightning não conectado")
        val address = getFixedPegOnchainAddress() ?: throw IllegalStateException("Não foi possível obter o endereço on-chain desta carteira.")
        return active.prepareSweepToOnchain(address)
    }

    suspend fun lightningConfirmSend(prepared: com.pokewallet.lightning.PreparedLightningPayment): breez_sdk_spark.Payment {
        val active = lightningWallet ?: throw IllegalStateException("Lightning não conectado")
        val payment = active.confirmSend(prepared)
        try {
            _lightningState.value = LightningState.Connected(active.balanceSats(ensureSynced = false))
        } catch (_: Exception) {}
        return payment
    }

    /** Nº de 429 (rate limit) consecutivos do autoScanJob — controla o
     *  backoff do próximo ciclo (ver AUTO_SCAN_MAX_BACKOFF_MS). Resetado a
     *  cada scan bem-sucedido. */
    private var consecutiveRateLimitHits = 0

    private fun nextAutoScanDelayMs(): Long {
        if (consecutiveRateLimitHits <= 0) return AUTO_SCAN_BASE_INTERVAL_MS
        val backoff = AUTO_SCAN_BASE_INTERVAL_MS * (1L shl minOf(consecutiveRateLimitHits, 6))
        return minOf(backoff, AUTO_SCAN_MAX_BACKOFF_MS)
    }

    private fun startAutoScan() {
        autoScanJob?.cancel()
        consecutiveRateLimitHits = 0
        loadPrice()
        loadFees()
        autoScanJob = viewModelScope.launch {
            doScan()
            while (true) {
                delay(nextAutoScanDelayMs())
                doScan()
                loadPrice()
                loadFees()
            }
        }
    }

    private fun nextSpAutoSyncDelayMs(): Long {
        if (spAutoSyncConsecutiveFailures <= 0) return SP_AUTO_SYNC_INTERVAL_MS
        val backoff = SP_AUTO_SYNC_INTERVAL_MS * (1L shl minOf(spAutoSyncConsecutiveFailures, 6))
        return minOf(backoff, SP_AUTO_SYNC_MAX_BACKOFF_MS)
    }

    /**
     * Sync automático de Silent Payments em segundo plano. Roda só pra
     * carteiras que realmente podem GASTAR o que receberem via SP
     * (BIP86/Taproot) e que tenham seed neste aparelho; BIP84 e watch-only
     * ficam de fora. O oracle precisa estar configurado pra rede ativa.
     */
    private fun startAutoSilentPaymentsSync(enabled: Boolean) {
        spAutoSyncJob?.cancel()
        spAutoSyncJob = null
        spAutoSyncRunning = false
        spAutoSyncConsecutiveFailures = 0
        lastSpAutoSyncTimeMs = null
        lastSpAutoSyncError = null

        val current = _walletState.value as? WalletState.Loaded ?: return
        if (!enabled || current.isWatchOnly) return

        spAutoSyncJob = viewModelScope.launch(Dispatchers.IO) {
            var first = true
            while (true) {
                delay(if (first) SP_AUTO_SYNC_FIRST_DELAY_MS else nextSpAutoSyncDelayMs())
                first = false

                if (!canAutoSyncSilentPayments()) continue

                spAutoSyncRunning = true
                try {
                    val result = syncSilentPayments(timeoutMs = SP_AUTO_SYNC_TIMEOUT_MS)
                    lastSpAutoSyncTimeMs = System.currentTimeMillis()
                    lastSpAutoSyncError = null
                    spAutoSyncConsecutiveFailures = 0
                    if (result.confirmedUtxos.isNotEmpty()) refreshNow()
                } catch (e: TimeoutCancellationException) {
                    lastSpAutoSyncError = "Sincronização automática demorou demais; o app tentará de novo."
                    spAutoSyncConsecutiveFailures++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastSpAutoSyncError = humanizeError(e)
                    spAutoSyncConsecutiveFailures++
                } finally {
                    spAutoSyncRunning = false
                }
            }
        }
    }

    private suspend fun canAutoSyncSilentPayments(): Boolean {
        val loaded = _walletState.value as? WalletState.Loaded ?: return false
        if (loaded.isWatchOnly) return false
        return try {
            val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
            if (wallet.spendType != SpendType.BIP86) return false
            val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
            BlindBitOraclePrefs.baseUrl(getApplication(), network) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun loadPrice() {
        viewModelScope.launch {
            try {
                val prices = withContext(Dispatchers.IO) { BlockstreamClient.getBtcPrices() }
                _priceState.value = prices
            } catch (_: Exception) {}
        }
    }

    fun getCurrentPrices(): BlockstreamClient.BtcPrices? = _priceState.value

    private fun loadFees() {
        viewModelScope.launch {
            try {
                val wallet  = withContext(Dispatchers.IO) { WalletStorage.load() }
                val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
                val fees    = withContext(Dispatchers.IO) { NodePrefs.dataSource(getApplication()).getFeeEstimates(network) }
                _feeState.value = fees
            } catch (_: Exception) {
                if (_feeState.value == null) _feeState.value = FeeEstimates.FALLBACK
            }
        }
    }

    /** Taxa sugerida (prioridade alta / confirmação mais rápida) pra pré-popular a UI de envio. */
    fun getCurrentFeeEstimates(): FeeEstimates =
        _feeState.value ?: FeeEstimates.FALLBACK

    /** Força uma varredura imediata (sem esperar o ciclo de 60s) — usado pelo
     *  botão Home, além do refresh automático já rodando em startAutoScan(). */
    fun refreshNow() {
        // Guarda simples contra toque repetido (ex.: usuário tocando Home
        // várias vezes rápido enquanto ansioso pra ver o saldo) empilhar
        // scans extras — cada um pesa dezenas de requests, e empilhar é
        // exatamente o que mais rápido leva a um 429 (ver AUTO_SCAN_*).
        if ((_walletState.value as? WalletState.Loaded)?.isScanning == true) return
        viewModelScope.launch { doScan() }
    }

    /** Força a PRÓXIMA varredura a ser completa (índice 0 em diante em vez de
     *  incremental) — corrige o caso de um endereço reservado (Receber/troco)
     *  ter ficado pra trás da fronteira do scan incremental antes de receber
     *  fundos, e por isso nunca mais é reverificado. Botão "🔁 Forçar rescan
     *  completo" na Mochila. */
    fun forceFullRescan() {
        if ((_walletState.value as? WalletState.Loaded)?.isScanning == true) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val wallet = WalletStorage.load()
                wallet.needsFullRescan = true
                WalletStorage.save(wallet)
            }
            doScan()
        }
    }

    private fun loadTxHistory(addresses: List<com.pokewallet.network.WalletScanner.ScannedAddress>, network: Network, txLog: List<TxLogEntry>) {
        viewModelScope.launch {
            try {
                val txMap = LinkedHashMap<String, WalletTx>()
                withContext(Dispatchers.IO) {
                    for (addr in addresses) {
                        val txJsons = BlockstreamClient.getAddressTxs(addr.address, network)
                        for (txJson in txJsons) {
                            val txid      = txJson.getString("txid")
                            val net       = BlockstreamClient.calcNetSats(txJson, addr.address)
                            val status    = txJson.getJSONObject("status")
                            val confirmed = status.getBoolean("confirmed")
                            val blockTime = if (confirmed) status.optLong("block_time", 0L).takeIf { it > 0 } else null
                            val existing  = txMap[txid]
                            txMap[txid] = if (existing != null)
                                existing.copy(netSats = existing.netSats + net)
                            else
                                WalletTx(txid, net, confirmed, blockTime)
                        }
                    }
                }
                // Mescla o histórico local persistido (ver TxLogEntry) — a
                // ÚNICA fonte pra qualquer transação que só envolve UTXOs
                // Silent Payments, já que o scan por endereço acima nunca
                // enxerga isso (output SP não é um endereço derivado do
                // xpub). Só entra se o txid ainda não veio do scan (que tem
                // status/valor real confirmados pela rede, sempre preferido
                // quando disponível).
                for (entry in txLog) {
                    if (txMap.containsKey(entry.txid)) continue
                    val net = when (entry.kind) {
                        TxLogEntry.KIND_RECEIVE_SP -> entry.amountSats ?: 0L
                        else                       -> -(entry.amountSats ?: 0L)
                    }
                    txMap[entry.txid] = WalletTx(entry.txid, net, confirmed = true, blockTime = entry.timestampMs / 1000)
                }
                _txHistory.value = txMap.values
                    .sortedByDescending { it.blockTime ?: Long.MAX_VALUE }
                    .take(50)
            } catch (_: Exception) {}
        }
    }

    private suspend fun doScan(): Unit = walletSwitchMutex.withLock {
        val current = _walletState.value as? WalletState.Loaded ?: return@withLock

        _walletState.value = current.copy(isScanning = true, scanStatus = "Varrendo endereços…")

        try {
            val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
            val xpub = wallet.xpub ?: return@withLock
            val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network

            // needsFullRescan (só true logo após migrar um wallet.json de
            // antes do scan incremental existir) força ignorar o estado
            // incremental UMA vez — sem isso, endereços antigos com saldo
            // (de antes desses campos existirem) ficariam invisíveis pro
            // scan incremental, que só re-verifica o que ele mesmo já sabe
            // que é ativo.
            val incremental = !wallet.needsFullRescan
            val result = withContext(Dispatchers.IO) {
                WalletScanner.scan(
                    xpub       = xpub,
                    network    = network,
                    spendType  = wallet.spendType,
                    startExternalIndex  = if (incremental) wallet.nextExternalIndex else 0,
                    startInternalIndex  = if (incremental) wallet.nextInternalIndex else 0,
                    knownActiveExternal = if (incremental) wallet.activeExternalIndices else emptySet(),
                    knownActiveInternal = if (incremental) wallet.activeInternalIndices else emptySet(),
                    onProgress = { _, index, _ ->
                        val loaded = _walletState.value as? WalletState.Loaded ?: return@scan
                        _walletState.value = loaded.copy(scanStatus = "Verificando endereço $index…")
                    },
                    dataSource = NodePrefs.dataSource(getApplication())
                )
            }

            // UTXOs Silent Payments só entram no saldo pra carteira BIP86 —
            // mesma condição de getUtxoList()/resolveSpend(): é exatamente
            // o que os torna GASTÁVEIS. Achado real: saldo ficava sem
            // contar fundos SP já confirmados e aparecendo na seleção
            // manual de envio, só não no saldo total da tela principal.
            val spUtxoSats = if (wallet.spendType == SpendType.BIP86) wallet.spUtxos.sumOf { it.valueSats } else 0L

            val confirmedSats = result.addressesWithFunds.sumOf { it.stats.confirmedSats } + spUtxoSats
            val pendingSats   = result.addressesWithFunds.sumOf { it.stats.pendingSats }
            val utxoCount     = result.addressesWithFunds.sumOf { it.utxos.size } + (if (wallet.spendType == SpendType.BIP86) wallet.spUtxos.size else 0)
            val scanTimeMs    = System.currentTimeMillis()

            withContext(Dispatchers.IO) {
                wallet.nextExternalIndex = result.nextExternalIndex
                wallet.nextInternalIndex = result.nextInternalIndex
                // allWithActivity inclui TANTO os índices já conhecidos
                // (re-verificados) QUANTO os novos achados na fronteira —
                // vira a lista completa e atualizada de índices ativos pro
                // PRÓXIMO scan incremental usar.
                wallet.activeExternalIndices = result.allWithActivity.filter { it.chain == 0 }.mapTo(mutableSetOf()) { it.index }
                wallet.activeInternalIndices = result.allWithActivity.filter { it.chain == 1 }.mapTo(mutableSetOf()) { it.index }
                wallet.needsFullRescan = false
                // Memória local do saldo (ver doc em WalletData) — próxima
                // vez que essa carteira abrir, loadWalletAndStartScan() pinta
                // a tela com isso na hora, sem esperar o scan de rede.
                wallet.cachedBalanceSats = confirmedSats
                wallet.cachedPendingSats = pendingSats
                wallet.cachedUtxoCount   = utxoCount
                wallet.cachedScanTimeMs  = scanTimeMs
                WalletStorage.save(wallet)
            }

            lastScanResult = result
            lastScanResultAtMs = System.currentTimeMillis()

            if (pendingSats > 0L && lastKnownPendingSats == 0L) {
                _pendingTxEvent.emit(pendingSats)
            }
            lastKnownPendingSats = pendingSats

            _walletState.value = current.copy(
                balanceSats   = confirmedSats,
                pendingSats   = if (pendingSats != 0L) pendingSats else null,
                utxoCount     = utxoCount,
                isScanning    = false,
                scanStatus    = null,
                lastScanTime  = Date(),
                lastScanError = null
            )

            // Opcional (Mochila → node Electrum → "Cruzar saldo com API
            // pública") — só roda quando o node próprio está ativo, já que
            // contra o Blockstream padrão a "fonte configurada" e a "API
            // pública" já são a mesma coisa. Best-effort: falha aqui
            // (Blockstream fora do ar, rate limit) não derruba o scan que
            // já teve sucesso, só não atualiza o aviso desta vez.
            if (NodePrefs.isEnabled(getApplication()) && NodePrefs.isCrossCheckEnabled(getApplication())
                && result.allWithActivity.isNotEmpty()
            ) {
                try {
                    val crossCheck = withContext(Dispatchers.IO) {
                        BalanceCrossChecker.check(result.allWithActivity, network)
                    }
                    val warning = if (crossCheck.divergenceSats > DUST_LIMIT_CROSS_CHECK_SATS) {
                        "⚠️ API pública reporta ${crossCheck.publicTotalSats} sat nos endereços conhecidos, " +
                            "seu node reportou ${crossCheck.configuredTotalSats} sat — possível saldo escondido pelo node."
                    } else null
                    val stillLoaded = _walletState.value as? WalletState.Loaded
                    if (stillLoaded != null) {
                        _walletState.value = stillLoaded.copy(balanceCrossCheckWarning = warning)
                    }
                } catch (_: Exception) {
                    // best-effort — ver comentário acima
                }
            }

            loadTxHistory(result.allWithActivity, network, wallet.txLog)
            consecutiveRateLimitHits = 0
        } catch (e: Exception) {
            // ANTES: erro era engolido em silêncio (só isScanning=false), saldo
            // ficava parado no valor antigo sem NENHUM aviso — de fora parecia
            // "o app não reconhece o saldo" quando na real o scan tava falhando
            // toda vez (rede, timeout, rate limit etc.) sem ninguém saber.
            val fallback = _walletState.value as? WalletState.Loaded ?: current
            _walletState.value = fallback.copy(
                isScanning    = false,
                scanStatus    = null,
                lastScanError = humanizeError(e)
            )
            // 429 confirmado em campo (Blockstream: 700 req/hora/IP) — o
            // próximo ciclo do autoScanJob espera mais (nextAutoScanDelayMs)
            // em vez de tentar de novo em 4 min e levar outro 429 quase
            // certo. Qualquer OUTRO tipo de erro (rede, timeout) não conta
            // pra esse backoff — só rate limit precisa de espera crescente.
            if (isRateLimitError(e)) consecutiveRateLimitHits++ else consecutiveRateLimitHits = 0
        }
    }

    private fun isRateLimitError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("429") || msg.contains("Too Many Requests", ignoreCase = true)
    }

    fun createWallet(
        network: Network = Network.MAINNET,
        passphraseMode: PassphraseMode = PassphraseMode.Pokemon,
        wordCount: Int = 24,
        spendType: SpendType = SpendType.BIP84,
        customName: String? = null,
        diceRolls: List<Int>? = null
    ) {
        _walletState.value = WalletState.Creating
        val context = getApplication<Application>()
        viewModelScope.launch {
            walletSwitchMutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        createWalletIntoPendingSlot(context) {
                            WalletInit.run(network, passphraseMode, wordCount, spendType, diceRolls)
                        }
                    }
                    val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                    WalletRegistry.setDisplayName(
                        context, wallet.fingerprint,
                        resolveDisplayName(wallet.fingerprint, customName, wallet.passphrase)
                    )
                    // Altura de nascimento — ponto de partida do primeiro scan
                    // de Silent Payments (ver WalletData.birthHeight): uma
                    // carteira RECÉM-CRIADA não pode ter recebido nada antes
                    // de existir. Best-effort — se a rede falhar aqui, a
                    // criação não pode travar por causa disso; fica null e o
                    // primeiro scan cai no lookback fixo (mais lento, mas
                    // seguro: nunca grava uma altura tardia que esconderia
                    // pagamentos SP recebidos logo após a criação).
                    withContext(Dispatchers.IO) {
                        try {
                            val tipHeight = NodePrefs.dataSource(context).getTipHeight(network)
                            wallet.birthHeight = tipHeight
                        } catch (_: Exception) {
                            // Sem rede/timeout — fica null (ver comentário acima).
                        }
                        WalletStorage.save(wallet)
                    }
                    // WalletInit.run() sempre cria carteira com seed — nunca watch-only.
                    _walletState.value = WalletState.Created(
                        mnemonic       = wallet.mnemonic!!.joinToString(" "),
                        passphrase     = wallet.passphrase!!,
                        passphraseMode = passphraseMode
                    )
                } catch (e: Exception) {
                    _walletState.value = WalletState.Error(humanizeError(e))
                }
            }
        }
    }

    /**
     * Cria uma carteira nova (via [block] = WalletInit.run ou
     * WalletRestore.run) num diretório temporário (wallets/_pending/) e só
     * move pro nome final (wallets/<fingerprint>/) depois de confirmar que
     * deu certo — WalletInit/WalletRestore calculam o fingerprint
     * internamente e não recebem diretório de destino, então não dá pra
     * saber o nome final da pasta antes de rodar.
     *
     * Cuidados (chamada só dentro de walletSwitchMutex):
     *  - _pending é sempre limpo ANTES de rodar [block] — WalletInit.run()
     *    NÃO lança exceção se já existir wallet.json no destino, só imprime
     *    e retorna, então não dá pra confiar em "sem exceção" como sinal de
     *    sucesso.
     *  - depois de [block], confere explicitamente que o arquivo foi criado.
     *  - aborta (sem sobrescrever) se já existir uma carteira com o mesmo
     *    fingerprint — a mesma seed já foi criada/importada antes.
     * Deixa WalletStorage.filesDir e a carteira ativa do registro apontando
     * pra carteira recém-criada quando retorna com sucesso.
     */
    private fun createWalletIntoPendingSlot(context: Application, block: () -> Unit): String {
        val pending = WalletRegistry.pendingDir(context.filesDir)
        pending.deleteRecursively()
        pending.mkdirs()

        WalletStorage.filesDir = pending
        block()

        check(File(pending, "wallet.json").exists()) {
            "Não foi possível criar a carteira — arquivo não foi gerado."
        }

        val fingerprint = WalletStorage.load().fingerprint
        val target = WalletRegistry.walletDir(context.filesDir, fingerprint)
        check(!target.exists()) {
            "Essa carteira (mesma seed) já existe."
        }
        check(pending.renameTo(target)) {
            "Falha ao salvar a carteira no local final."
        }

        WalletStorage.filesDir = target
        WalletRegistry.setActiveWalletId(context, fingerprint)
        return fingerprint
    }

    /** Nome de exibição: nome customizado > nome do Pokémon (se a passphrase
     *  estiver nesse formato) > "Carteira XXXX" a partir do fingerprint.
     *  passphrase é null numa carteira watch-only (sem seed) — pula direto
     *  pro fallback nesse caso, sem tentar casar o regex do Pokémon. */
    private fun resolveDisplayName(fingerprint: String, customName: String?, passphrase: String?): String {
        if (!customName.isNullOrBlank()) return customName.trim()
        if (passphrase != null) {
            POKEMON_PASSPHRASE_REGEX.find(passphrase)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return WalletRegistry.displayNameOrFallback(getApplication(), fingerprint)
    }

    /** Fingerprint, nome de exibição e se é watch-only de uma carteira conhecida no disco. */
    data class KnownWallet(val fingerprint: String, val displayName: String, val isWatchOnly: Boolean)

    /** Lista todas as carteiras conhecidas no disco. */
    fun listKnownWallets(): List<KnownWallet> {
        val context = getApplication<Application>()
        return WalletRegistry.listKnownWalletIds(context.filesDir).map { id ->
            KnownWallet(
                fingerprint = id,
                displayName = WalletRegistry.displayNameOrFallback(context, id),
                isWatchOnly = WalletStorage.peekIsWatchOnly(WalletRegistry.walletDir(context.filesDir, id))
            )
        }
    }

    /** Troca a carteira ativa pra [fingerprint] (já precisa existir em disco). */
    fun switchWallet(fingerprint: String) {
        val context = getApplication<Application>()
        val current = _walletState.value as? WalletState.Loaded
        if (current?.fingerprint == fingerprint) return

        viewModelScope.launch {
            var switched = false
            walletSwitchMutex.withLock {
                _walletState.value = WalletState.Loading
                try {
                    withContext(Dispatchers.IO) {
                        WalletStorage.filesDir = WalletRegistry.walletDir(context.filesDir, fingerprint)
                        check(WalletStorage.exists()) { "Carteira não encontrada" }
                    }
                    WalletRegistry.setActiveWalletId(context, fingerprint)
                    switched = true
                } catch (e: Exception) {
                    _walletState.value = WalletState.Error(humanizeError(e))
                }
            }
            // Só recarrega se a troca deu certo — senão isso sobrescreveria o
            // Error acima tentando carregar de um filesDir que pode ter ficado
            // apontando pra um lugar inválido.
            if (switched) loadWalletAndStartScan()
        }
    }

    /** Renomeia a carteira ativa (só o rótulo local, não mexe no wallet.json). */
    fun renameActiveWallet(newName: String) {
        if (newName.isBlank()) return
        val current = _walletState.value as? WalletState.Loaded ?: return
        val context = getApplication<Application>()
        val trimmed = newName.trim()
        WalletRegistry.setDisplayName(context, current.fingerprint, trimmed)
        _walletState.value = current.copy(displayName = trimmed)
    }

    fun onMnemonicConfirmed() {
        viewModelScope.launch {
            walletSwitchMutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        val wallet = WalletStorage.load()
                        wallet.raw.put("mnemonicVerified", true)
                        WalletStorage.save(wallet)
                    }
                } catch (_: Exception) {
                    // Se a persistência falhar, a próxima abertura do app volta a pedir a
                    // verificação — chato, mas seguro (não libera acesso sem o flag salvo).
                }
            }
            loadWalletAndStartScan()
        }
    }

    fun getReceiveAddress(): Pair<String, Int>? {
        // tryLock (não-suspend) em vez de withLock: esta função é chamada
        // sincronamente da UI. Se uma troca/criação/scan estiver segurando o
        // mutex nesse instante, desiste e devolve null (mesmo comportamento
        // de qualquer outra falha aqui) em vez de bloquear a UI esperando.
        if (!walletSwitchMutex.tryLock()) return null
        return try {
            val wallet = WalletStorage.load()
            // Reserva atômica — evita que dois toques em "Receber" concorrentes
            // (ou um toque colidindo com o índice de troco de um envio) derivem
            // o mesmo índice/endereço.
            val index = WalletStorage.reserveNextExternalIndex()

            if (wallet.isWatchOnly) {
                val xpub = wallet.xpub ?: return null
                val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
                val address = deriveWatchOnlyAddress(xpub, chain = 0, index = index, network = network, spendType = wallet.spendType)
                return Pair(address, index)
            }

            val seed = SeedDerivation.fromMnemonic(wallet.mnemonic!!, wallet.passphrase!!)
            try {
                val address = ReceiveAddressService.addressAt(
                    seed      = seed,
                    spendType = wallet.spendType,
                    network   = wallet.network,
                    index     = index
                )
                Pair(address, index)
            } finally {
                seed.fill(0)
            }
        } catch (_: Exception) {
            null
        } finally {
            walletSwitchMutex.unlock()
        }
    }

    /**
     * Endereço Silent Payments (BIP-352) único desta carteira — ao
     * contrário de [getReceiveAddress], não avança índice nenhum (SP não
     * usa cadeia de endereços, é uma chave scan/spend fixa por carteira).
     * Só carteiras com seed neste aparelho — watch-only "somente scan"
     * (chave de scan privada + spend pública) é a Fase 5 do plano, ainda
     * não implementada.
     */
    fun getSilentPaymentAddress(): String? {
        if (!walletSwitchMutex.tryLock()) return null
        return try {
            val wallet = WalletStorage.load()
            if (wallet.isWatchOnly) return null
            // Mesmo colapso REGTEST->TESTNET de buildSignedTx() — o envio pra
            // SP (TxAssembler.resolveSilentPaymentDestination) decodifica o
            // destino com a rede JÁ colapsada, então o endereço mostrado aqui
            // precisa ser codificado com o mesmo HRP, senão uma carteira
            // REGTEST nunca consegue decodificar o PRÓPRIO endereço de volta
            // (HRP "sprt" != "tsp").
            val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
            val seed = SeedDerivation.fromMnemonic(wallet.mnemonic!!, wallet.passphrase!!)
            try {
                SilentPaymentAddressService.ownAddress(seed, network)
            } finally {
                seed.fill(0)
            }
        } catch (_: Exception) {
            null
        } finally {
            walletSwitchMutex.unlock()
        }
    }

    /**
     * Escaneia recebimento Silent Payments (BIP-352, Fase 3): descobre até
     * onde o oracle já indexou, escaneia [wallet.spScanTipHeight]+1 em
     * diante (ou [SilentPaymentsSync.DEFAULT_LOOKBACK_BLOCKS] blocos pra
     * trás do tip, se for o primeiro scan desta carteira), CONFIRMA cada
     * candidato contra a fonte de dados própria já configurada (Electrum/
     * Blockstream, nunca só o filtro de 8 bytes do oracle — ver
     * [com.pokewallet.network.SilentPaymentsConfirmer]) e persiste os
     * confirmados. Só carteira com seed neste aparelho (mesma limitação de
     * [getSilentPaymentAddress] — watch-only "somente scan" é a Fase 5,
     * ainda não implementada).
     *
     * [onProgress], se dado, é chamado depois de cada bloco processado
     * (altura atual, início, fim do range) — a UI usa isso pra mostrar
     * progresso real em vez de um "Sincronizando…" indefinido (cada bloco é
     * uma leva de multiplicações de ponto de curva elíptica, pode
     * legitimamente demorar num celular sem aceleração de hardware — sem
     * progresso visível, não dá pra distinguir "lento mas funcionando" de
     * "travado"). [timeoutMs] é uma rede de segurança: se o scan não
     * terminar nesse tempo (rede/oracle travado de verdade), falha com erro
     * claro em vez de ficar preso pra sempre segurando [walletSwitchMutex]
     * (que bloquearia qualquer outra operação na carteira indefinidamente).
     *
     * [rescanFromHeight], quando informado, IGNORA tanto o progresso salvo
     * ([WalletData.spScanTipHeight]) quanto [WalletData.birthHeight] e força
     * o scan a começar exatamente dessa altura — bug real encontrado numa
     * restauração de carteira (2026-09-04): o primeiro scan de uma carteira
     * restaurada não conhece [WalletData.birthHeight] (não dá pra saber a
     * altura de nascimento real só a partir da mnemonic), então cai no
     * lookback fixo de [SilentPaymentsSync.DEFAULT_LOOKBACK_BLOCKS] (100
     * blocos) — pagamentos SP recebidos antes disso nunca são vistos. Pior:
     * depois desse primeiro scan incompleto, [WalletData.spScanTipHeight]
     * já fica gravado no tip, então TODO sync futuro parte dali pra frente
     * e a lacuna antiga fica invisível pra sempre sem um jeito manual de
     * voltar. Usa o mesmo campo (previousScanTipHeight = altura-1) que já
     * prioriza sobre birthHeight/lookback em [SilentPaymentsSync.resolveStartHeight].
     */
    suspend fun syncSilentPayments(
        onProgress: (height: Long, start: Long, end: Long) -> Unit = { _, _, _ -> },
        timeoutMs: Long = 15 * 60_000L,
        rescanFromHeight: Long? = null
    ): SilentPaymentsSync.SyncResult = withTimeout(timeoutMs) {
        spSyncMutex.withLock {
            walletSwitchMutex.withLock {
            val wallet = WalletStorage.load()
            require(!wallet.isWatchOnly) {
                "Esta carteira é watch-only (sem seed neste aparelho) — scan de Silent Payments ainda exige a carteira com a seed."
            }
            // Mesmo colapso de getSilentPaymentAddress()/buildSignedTx() — ver
            // nota lá sobre HRP consistente entre as fases.
            val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
            val context = getApplication<Application>()
            val oracleUrl = BlindBitOraclePrefs.baseUrl(context, network)
                ?: error("Nenhum host de oracle configurado pra esta rede — configure um manualmente (sem instância pública conhecida pra REGTEST).")
            // "Confirmar via Tor" (toggle na Mochila) troca só a fonte usada
            // pra CONFIRMAR os candidatos SP (busca de tx bruta por txid) —
            // não afeta saldo/scan normal do resto do app, nem a comunicação
            // com o oracle (que não passa por [ChainDataSource] nenhum). Ver
            // doc de [BlindBitOraclePrefs.isConfirmViaTorEnabled].
            val confirmViaTor = BlindBitOraclePrefs.isConfirmViaTorEnabled(context)
            val primaryDataSource = if (confirmViaTor) {
                TorBlockstreamDataSource(TorPrefs.proxy(context))
            } else {
                NodePrefs.dataSource(context)
            }
            // Fallback pra pool de Electrum públicos (ver FailoverChainDataSource):
            // Floresta não serve tx histórica arbitrária (Utreexo, sem
            // índice) e Blockstream/mempool.space aplicam rate limit por IP
            // — os dois casos reais que travavam a confirmação de SP. Regras:
            // 1) ativo por padrão (BlindBitOraclePrefs.isElectrumFallbackEnabled),
            // 2) só MAINNET (única com lista pública curada), 3) NUNCA junto de
            // "Confirmar via Tor" — o fallback é clearnet e vazaria o IP real.
            val useFailover = network == Network.MAINNET &&
                !confirmViaTor &&
                BlindBitOraclePrefs.isElectrumFallbackEnabled(context)
            val dataSource = if (useFailover) {
                FailoverChainDataSource(primaryDataSource)
            } else {
                primaryDataSource
            }

            val seed = SeedDerivation.fromMnemonic(wallet.mnemonic!!, wallet.passphrase!!)
            val scanPriv: ByteArray
            val spendPub: ByteArray
            try {
                scanPriv = Bip352KeyDerivation.scanKey(seed, network).privateKey
                spendPub = Secp256k1.publicKeyFromPrivate(Bip352KeyDerivation.spendKey(seed, network).privateKey)
            } finally {
                seed.fill(0)
            }

            try {
                val result = SilentPaymentsSync.sync(
                    oracleBaseUrl         = oracleUrl,
                    dataSource            = dataSource,
                    network               = network,
                    scanPrivateKey        = scanPriv,
                    spendPubKey           = spendPub,
                    previousScanTipHeight = rescanFromHeight?.let { maxOf(0L, it - 1) } ?: wallet.spScanTipHeight,
                    birthHeight           = wallet.birthHeight,
                    onBlockScanned        = onProgress,
                    onChunkCompleted      = { chunkUtxos, chunkTipHeight ->
                        // Persiste o progresso a cada 100 blocos. Se o stream
                        // morrer no chunk seguinte, não se perde o que já foi
                        // confirmado e o próximo sync continua do chunk salvo.
                        WalletStorage.addSilentPaymentUtxos(chunkUtxos, chunkTipHeight)
                    }
                )
                if (result.confirmedUtxos.isNotEmpty() || result.newScanTipHeight != wallet.spScanTipHeight) {
                    WalletStorage.addSilentPaymentUtxos(result.confirmedUtxos, result.newScanTipHeight)
                }
                lastSpSyncUsedFallback = (dataSource as? FailoverChainDataSource)?.usedFallback ?: false
                result
                } finally {
                    scanPriv.fill(0)
                    (dataSource as? FailoverChainDataSource)?.close()
                }
            }
        }
    }

    /** Dados pra UI mostrar o status do scan Silent Payments (Mochila,
     *  seção "Silent Payments") — [network] já colapsada (REGTEST->TESTNET,
     *  mesma convenção do resto do fluxo SP) pra bater com o host resolvido
     *  por [BlindBitOraclePrefs]. */
    data class SilentPaymentSyncStatus(
        val network: Network,
        val oracleHost: String?,
        val oracleTlsEnabled: Boolean,
        val lastScanTipHeight: Long,
        val knownUtxoCount: Int,
        val isWatchOnly: Boolean,
        val lastSyncUsedFallback: Boolean,
        val isAutoSyncEnabled: Boolean,
        val isAutoSyncRunning: Boolean,
        val lastAutoSyncTimeMs: Long?,
        val lastAutoSyncError: String?
    )

    fun getSilentPaymentSyncStatus(): SilentPaymentSyncStatus? {
        if (!walletSwitchMutex.tryLock()) return null
        return try {
            val wallet = WalletStorage.load()
            val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
            val context = getApplication<Application>()
            SilentPaymentSyncStatus(
                network           = network,
                oracleHost        = BlindBitOraclePrefs.host(context, network),
                oracleTlsEnabled  = BlindBitOraclePrefs.isTlsEnabled(context),
                lastScanTipHeight = wallet.spScanTipHeight,
                knownUtxoCount    = wallet.spUtxos.size,
                isWatchOnly       = wallet.isWatchOnly,
                lastSyncUsedFallback = lastSpSyncUsedFallback,
                isAutoSyncEnabled  = spAutoSyncJob?.isActive == true,
                isAutoSyncRunning  = spAutoSyncRunning,
                lastAutoSyncTimeMs = lastSpAutoSyncTimeMs,
                lastAutoSyncError  = lastSpAutoSyncError
            )
        } catch (_: Exception) {
            null
        } finally {
            walletSwitchMutex.unlock()
        }
    }

    /**
     * "[fingerprint/purpose'/coin'/0']xpub" da carteira ativa — a mesma
     * string que dá pra colar (ou, quando o QR scanner for adicionado,
     * escanear) na tela de importação watch-only de outro aparelho.
     * Nenhum dado secreto: é só a chave pública, segura de exportar.
     */
    /** accountOrigin pronto pra EXIBIÇÃO — mesmo dado salvo em wallet.json,
     *  só com o prefixo da xpub reescrito pra zpub/vpub quando a carteira é
     *  BIP84 (Native SegWit), formato que outras wallets tipo Electrum/
     *  Sparrow também usam pra esse tipo de endereço. Puramente cosmético:
     *  o valor salvo internamente continua "xpub" sempre (ver
     *  ExtendedKeySerializer.toDisplayPrefix()). */
    fun getAccountOrigin(): String? {
        if (!walletSwitchMutex.tryLock()) return null
        return try {
            val wallet = WalletStorage.load()
            val origin = wallet.accountOrigin ?: return null
            val xpub = wallet.xpub ?: return origin
            val displayXpub = com.pokewallet.crypto.ExtendedKeySerializer.toDisplayPrefix(xpub, wallet.spendType, wallet.network)
            if (displayXpub == xpub) origin else origin.replace(xpub, displayXpub)
        } catch (_: Exception) {
            null
        } finally {
            walletSwitchMutex.unlock()
        }
    }

    /**
     * Lista os endereços derivados (recebimento + troco) da carteira ativa,
     * com saldo/uso conhecidos pelo último scan. Deriva só a partir do xpub
     * (nunca toca mnemonic) — funciona igual pra carteira com seed ou
     * watch-only. Mesmo padrão tryLock de getReceiveAddress(): desiste em
     * vez de bloquear se uma troca/scan estiver em andamento.
     */
    fun getAddressList(): List<AddressRow>? {
        if (!walletSwitchMutex.tryLock()) return null
        return try {
            val wallet  = WalletStorage.load()
            val xpub    = wallet.xpub ?: return null
            val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
            val scan    = lastScanResult

            val activityByKey = scan?.allWithActivity?.associateBy { it.chain to it.index } ?: emptyMap()
            val fundsByKey    = scan?.addressesWithFunds?.associateBy { it.chain to it.index } ?: emptyMap()

            val rows = mutableListOf<AddressRow>()
            for (chain in 0..1) {
                val reserved        = if (chain == 0) wallet.nextExternalIndex else wallet.nextInternalIndex
                val highestActivity = activityByKey.keys.filter { it.first == chain }.maxOfOrNull { it.second + 1 } ?: 0
                val count           = maxOf(reserved, highestActivity)
                for (index in 0 until count) {
                    val key     = chain to index
                    val scanned = activityByKey[key] ?: fundsByKey[key]
                    val address = scanned?.address ?: deriveWatchOnlyAddress(xpub, chain, index, network, wallet.spendType)
                    rows += AddressRow(
                        chain       = chain,
                        index       = index,
                        address     = address,
                        balanceSats = fundsByKey[key]?.balanceSats ?: 0L,
                        used        = activityByKey.containsKey(key)
                    )
                }
            }
            rows
        } catch (_: Exception) {
            null
        } finally {
            walletSwitchMutex.unlock()
        }
    }

    private fun deriveWatchOnlyAddress(
        xpub: String, chain: Int, index: Int, network: Network, spendType: SpendType
    ): String = when (spendType) {
        SpendType.BIP84 -> com.pokewallet.network.XpubAddressDeriver.p2wpkhAddress(xpub, chain, index, network)
        SpendType.BIP86 -> com.pokewallet.network.XpubAddressDeriver.p2trAddress(xpub, chain, index, network)
    }

    /**
     * Lista os UTXOs conhecidos pelo último scan, com o estado "congelado"
     * persistido em wallet.json. Se ainda não houve nenhum scan (app recém
     * aberto), devolve lista vazia em vez de null — a tela de UTXOs não
     * precisa de índice/endereço derivado ao vivo como a de endereços,
     * só o que o scan já encontrou.
     */
    /**
     * UTXOs derivados normalmente + UTXOs Silent Payments (BIP-352), estes
     * últimos SÓ quando a carteira é BIP86 — exatamente a mesma condição
     * que os torna GASTÁVEIS (ver resolveSpend()/SpendResolver.candidatesFrom):
     * mostrar sem poder gastar (BIP84) seria mais confuso que não mostrar.
     * UtxoRow.chain = -1 sinaliza "é SP" pro renderer da UI (WalletFragment).
     */
    fun getUtxoList(): List<UtxoRow>? {
        if (!walletSwitchMutex.tryLock()) return null
        return try {
            val wallet = WalletStorage.load()
            val scan = lastScanResult
            val derived = scan?.addressesWithFunds?.flatMap { addr ->
                addr.utxos.map { utxo ->
                    val key = "${utxo.txid}:${utxo.vout}"
                    UtxoRow(
                        txid      = utxo.txid,
                        vout      = utxo.vout,
                        valueSats = utxo.valueSats,
                        confirmed = utxo.confirmed,
                        address   = addr.address,
                        chain     = addr.chain,
                        index     = addr.index,
                        frozen    = wallet.frozenUtxoKeys.contains(key)
                    )
                }
            } ?: emptyList()

            val silentPayments = if (wallet.spendType == SpendType.BIP86) {
                wallet.spUtxos.map { spUtxo ->
                    val key = "${spUtxo.txid}:${spUtxo.vout}"
                    UtxoRow(
                        txid      = spUtxo.txid,
                        vout      = spUtxo.vout,
                        valueSats = spUtxo.valueSats,
                        confirmed = true,
                        address   = "Silent Payments",
                        chain     = -1,
                        index     = -1,
                        frozen    = wallet.frozenUtxoKeys.contains(key)
                    )
                }
            } else emptyList()

            (derived + silentPayments).sortedByDescending { it.valueSats }
        } catch (_: Exception) {
            null
        } finally {
            walletSwitchMutex.unlock()
        }
    }

    /** Congela/descongela um UTXO específico. Protegido pelo mesmo mutex de
     *  toda operação que muta wallet.json, pra não gravar no arquivo errado
     *  se uma troca de carteira acontecer no meio. */
    fun toggleUtxoFrozen(txid: String, vout: Int, freeze: Boolean) {
        viewModelScope.launch {
            walletSwitchMutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        WalletStorage.setUtxoFrozen("$txid:$vout", freeze)
                    }
                } catch (_: Exception) {
                    // Falha ao persistir: a UI simplesmente não reflete a mudança
                    // (próxima leitura de getUtxoList() volta a mostrar o estado
                    // anterior) — não há saldo em risco, só um toggle que não pegou.
                }
            }
        }
    }

    fun sendFunds(
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        mode: SendMode = SendMode.Internet,
        feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>? = null
    ) {
        _sendState.value = SendState.Sending
        viewModelScope.launch {
            try {
                require(feeRateSatPerVbyte >= 0.5) { "Taxa mínima é 0.5 sat/vB" }
                when (mode) {
                    is SendMode.Internet -> {
                        val txid = withContext(Dispatchers.IO) {
                            executeSend(destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)
                        }
                        _sendState.value = SendState.Success(txid, confirmedByRelay = true)
                    }
                    is SendMode.Tor -> {
                        val txid = withContext(Dispatchers.IO) {
                            executeSendViaTor(destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)
                        }
                        _sendState.value = SendState.Success(txid, confirmedByRelay = true)
                    }
                    is SendMode.BitChat -> {
                        val result = withContext(Dispatchers.IO) {
                            executeSendViaNostr(destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)
                        }
                        _sendState.value = SendState.Success(
                            txid             = result.txid,
                            confirmedByRelay = result.confirmed,
                            relayReplyText   = result.replyText
                        )
                    }
                }
                doScan()
            } catch (e: Exception) {
                _sendState.value = SendState.Error(humanizeError(e))
            }
        }
    }

    fun resetSendState() { _sendState.value = SendState.Idle }

    fun restoreWallet(
        words: List<String>,
        passphrase: String,
        network: Network,
        spendType: SpendType = SpendType.BIP84,
        customName: String? = null
    ) {
        _restoreState.value = RestoreState.Restoring
        val context = getApplication<Application>()
        viewModelScope.launch {
            var restored = false
            walletSwitchMutex.withLock {
                try {
                    val fingerprint = withContext(Dispatchers.IO) {
                        createWalletIntoPendingSlot(context) {
                            com.pokewallet.crypto.WalletRestore.run(words, passphrase, network, spendType)
                        }
                    }
                    WalletRegistry.setDisplayName(
                        context, fingerprint,
                        resolveDisplayName(fingerprint, customName, passphrase)
                    )
                    _restoreState.value = RestoreState.Success
                    restored = true
                } catch (e: Exception) {
                    _restoreState.value = RestoreState.Error(humanizeError(e))
                }
            }
            // Só recarrega se a restauração deu certo — senão isso poderia
            // tentar carregar de um _pending inválido/incompleto e mascarar
            // o erro de verdade que já foi mostrado acima.
            if (restored) loadWalletAndStartScan()
        }
    }

    fun resetRestoreState() { _restoreState.value = RestoreState.Idle }

    /** Importa uma carteira watch-only (só xpub, sem seed) — mesmo formato
     *  staging→rename de restoreWallet()/createWallet(), só troca o bloco
     *  que grava wallet.json (WalletWatchOnlyImport.run em vez de
     *  WalletRestore.run). Nome de exibição sem Pokémon (não há passphrase).
     *
     *  [forgetKeyedFingerprint] só é passado depois que o usuário já
     *  confirmou explicitamente (via tela de conflito) que quer esquecer a
     *  carteira COM CHAVE de mesmo fingerprint que já existe neste
     *  aparelho e trocar por esta versão watch-only. Sem confirmação
     *  prévia, uma colisão desse tipo pára em
     *  WatchOnlyImportState.ConflictWithKeyedWallet em vez de seguir. */
    fun importWatchOnly(
        accountOrigin: String,
        network: Network,
        spendType: SpendType,
        customName: String? = null,
        forgetKeyedFingerprint: String? = null
    ) {
        _watchOnlyImportState.value = WatchOnlyImportState.Importing
        val context = getApplication<Application>()
        viewModelScope.launch {
            var imported = false
            walletSwitchMutex.withLock {
                try {
                    val parsed = WalletWatchOnlyImport.parse(accountOrigin, network, spendType)
                    val existingDir = WalletRegistry.walletDir(context.filesDir, parsed.fingerprintHex)
                    val existingIsKeyed = withContext(Dispatchers.IO) {
                        existingDir.exists() && !WalletStorage.peekIsWatchOnly(existingDir)
                    }
                    if (existingIsKeyed && forgetKeyedFingerprint != parsed.fingerprintHex) {
                        _watchOnlyImportState.value = WatchOnlyImportState.ConflictWithKeyedWallet(parsed.fingerprintHex)
                        return@withLock
                    }
                    if (existingIsKeyed) {
                        // Confirmado pelo usuário: só apaga o diretório (NÃO mexe no
                        // nome/registro em WalletRegistry — mesmo fingerprint, então
                        // o nome de exibição já cadastrado continua valendo pra versão
                        // watch-only que vai ocupar o lugar).
                        withContext(Dispatchers.IO) { existingDir.deleteRecursively() }
                    }
                    val fingerprint = withContext(Dispatchers.IO) {
                        createWalletIntoPendingSlot(context) {
                            WalletWatchOnlyImport.run(accountOrigin, network, spendType)
                        }
                    }
                    WalletRegistry.setDisplayName(
                        context, fingerprint,
                        resolveDisplayName(fingerprint, customName, passphrase = null)
                    )
                    _watchOnlyImportState.value = WatchOnlyImportState.Success
                    imported = true
                } catch (e: Exception) {
                    _watchOnlyImportState.value = WatchOnlyImportState.Error(humanizeError(e))
                }
            }
            if (imported) loadWalletAndStartScan()
        }
    }

    fun resetWatchOnlyImportState() { _watchOnlyImportState.value = WatchOnlyImportState.Idle }

    /** Esquece só a carteira ATIVA — troca pra outra carteira conhecida se
     *  sobrar alguma, ou volta pra tela de criação se era a última. */
    fun forgetWallet() {
        val context = getApplication<Application>()
        val forgottenId = (_walletState.value as? WalletState.Loaded)?.fingerprint

        viewModelScope.launch {
            var next: String? = null
            walletSwitchMutex.withLock {
                resetPerWalletCaches()
                withContext(Dispatchers.IO) {
                    WalletStorage.delete()
                    if (forgottenId != null) {
                        WalletRegistry.walletDir(context.filesDir, forgottenId).deleteRecursively()
                        WalletRegistry.removeWallet(context, forgottenId)
                    }
                }

                next = WalletRegistry.listKnownWalletIds(context.filesDir).firstOrNull()
                if (next != null) {
                    withContext(Dispatchers.IO) {
                        WalletStorage.filesDir = WalletRegistry.walletDir(context.filesDir, next!!)
                    }
                    WalletRegistry.setActiveWalletId(context, next!!)
                } else {
                    WalletRegistry.clearActiveWalletId(context)
                    _walletState.value = WalletState.NoWallet
                }
            }
            if (next != null) loadWalletAndStartScan()
        }
    }

    /** Transação assinada, pronta pra transmitir por qualquer um dos dois caminhos. */
    private data class PreparedTx(
        val rawTxHex: String,
        val txid: String,
        val seed: ByteArray,
        val network: Network,
        // "txid:vout" dos UTXOs Silent Payments (wallet.spUtxos) que entraram
        // nesta tx — precisa sair de spUtxos assim que o broadcast realmente
        // acontecer, senão fica contando no saldo e sendo oferecido de novo
        // pra sempre (bug real: spUtxos só tinha código de ADIÇÃO, nunca de
        // remoção — ver WalletStorage.removeSilentPaymentUtxos).
        val spentSilentPaymentUtxoKeys: Set<String> = emptySet(),
        // Soma bruta dos UTXOs escolhidos (antes de troco/fee) — usado só
        // pra registrar um valor no histórico local (TxLogEntry) quando o
        // envio é sweep, onde o parâmetro amountSats do chamador é null.
        val totalInputSats: Long = 0L
    )

    private suspend fun executeSend(
        destination: String, amountSats: Long?, sweep: Boolean, feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>? = null
    ): String {
        val prepared = buildSignedTx(destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)
        try {
            val txid = NodePrefs.dataSource(getApplication()).broadcast(prepared.rawTxHex, prepared.network)
            WalletStorage.removeSilentPaymentUtxos(prepared.spentSilentPaymentUtxoKeys)
            WalletStorage.appendSendLogEntry(txid, amountSats ?: prepared.totalInputSats, prepared.spentSilentPaymentUtxoKeys.isNotEmpty())
            return txid
        } finally {
            prepared.seed.fill(0)
        }
    }

    /**
     * Igual a [executeSend], mas o broadcast sai roteado pelo proxy SOCKS5
     * do Orbot (ver [TorPrefs]) — sempre via Blockstream/mempool.space
     * (não pelo node próprio configurado em [NodePrefs], que é um caminho
     * separado). Se o Orbot não estiver rodando na porta configurada, a
     * conexão falha com erro claro (recusada) em vez de vazar pra fora do
     * Tor silenciosamente — não há fallback automático pra internet direta.
     */
    private suspend fun executeSendViaTor(
        destination: String, amountSats: Long?, sweep: Boolean, feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>? = null
    ): String {
        val prepared = buildSignedTx(destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)
        try {
            val proxy = TorPrefs.proxy(getApplication())
            return try {
                val txid = BlockstreamClient.broadcastViaProxy(prepared.rawTxHex, prepared.network, proxy)
                WalletStorage.removeSilentPaymentUtxos(prepared.spentSilentPaymentUtxoKeys)
                WalletStorage.appendSendLogEntry(txid, amountSats ?: prepared.totalInputSats, prepared.spentSilentPaymentUtxoKeys.isNotEmpty())
                txid
            } catch (e: Exception) {
                throw RuntimeException(
                    "Não foi possível transmitir via Tor — confira se o Orbot está instalado, " +
                        "rodando, e com o proxy SOCKS habilitado em " +
                        "${TorPrefs.getHost(getApplication())}:${TorPrefs.getPort(getApplication())}. (${e.message})",
                    e
                )
            }
        } finally {
            prepared.seed.fill(0)
        }
    }

    private suspend fun executeSendViaNostr(
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>? = null
    ): NostrSendResult {
        val prepared = buildSignedTx(destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)

        _sendState.value = SendState.PublishingToRelays

        // Identidade EFÊMERA (não derivada da seed) — o bot correlaciona a
        // resposta pelo txid no conteúdo, não pela pubkey do publicador,
        // então usar uma chave nova a cada envio não quebra o protocolo, e
        // fecha o vetor de qualquer observador do canal público conseguir
        // clusterizar todos os envios Nostr desta wallet pela mesma pubkey
        // (o que uma identidade fixa via NIP-06 permitiria).
        val (nostrPrivKey, nostrPubKey) = NostrKeys.random()
        prepared.seed.fill(0)
        val relays = GeoRelayDirectory.closestRelays(BITCHAT_GEOHASH)
        val event = try {
            NostrEvent.build(
                privKey32 = nostrPrivKey,
                pubKey32  = nostrPubKey,
                kind      = 20000,
                tags      = listOf(listOf("g", BITCHAT_GEOHASH)),
                content   = "!broadcast ${prepared.rawTxHex}"
            )
        } finally {
            nostrPrivKey.fill(0)
        }

        _sendState.value = SendState.AwaitingRelayConfirmation(prepared.txid)

        val result = NostrRelayClient.publishAndAwaitReply(
            event                  = event,
            relays                 = relays,
            ourPubkeyHex           = event.pubkey,
            geohash                = BITCHAT_GEOHASH,
            timeoutMs              = 18_000L,
            expectedReplyPubkeyHex = BITCHAT_BROADCASTER_PUBKEY_HEX
        ) { content -> content.contains(prepared.txid, ignoreCase = true) }

        if (!result.published) {
            error("Não foi possível publicar via Nostr — nenhum relay confirmou o recebimento.")
        }

        val confirmed = result.replyContent != null
        // Só tira da lista SP quando o bot CONFIRMOU o broadcast — sem essa
        // confirmação não dá pra saber se ele processou a mensagem, e tirar
        // sem certeza esconderia saldo de um UTXO que na verdade não foi
        // gasto (pior que o bug original, que só reoferece um UTXO já gasto).
        if (confirmed) {
            WalletStorage.removeSilentPaymentUtxos(prepared.spentSilentPaymentUtxoKeys)
            WalletStorage.appendSendLogEntry(prepared.txid, amountSats ?: prepared.totalInputSats, prepared.spentSilentPaymentUtxoKeys.isNotEmpty())
        }

        return NostrSendResult(
            txid      = prepared.txid,
            confirmed = confirmed,
            replyText = result.replyContent
        )
    }

    /**
     * Reusa o cache de doScan() se ainda estiver fresco (mesma rede,
     * dentro de SCAN_CACHE_TTL_MS) — evita repetir um scan completo
     * (5-15s, dezenas de requests HTTP) logo depois de um já ter rodado.
     * Caso contrário faz um scan novo e atualiza o cache.
     */
    private suspend fun scanForSend(
        xpub: String,
        network: Network,
        spendType: SpendType
    ): com.pokewallet.network.WalletScanner.ScanResult {
        val cached = lastScanResult
        val fresh = cached != null &&
            cached.network == network &&
            System.currentTimeMillis() - lastScanResultAtMs < SCAN_CACHE_TTL_MS

        if (fresh) return cached!!

        val result = WalletScanner.scan(
            xpub = xpub, network = network, spendType = spendType,
            dataSource = NodePrefs.dataSource(getApplication())
        )
        lastScanResult = result
        lastScanResultAtMs = System.currentTimeMillis()
        return result
    }

    /**
     * Escaneia (ou reusa o cache) e verifica valor contra a tx anterior
     * real (rede, ver UtxoValueVerifier) — a decisão em si de QUAIS UTXOs
     * entram na tx (manual > sweep > CoinSelector automático) + quanto vai
     * pro destino/troco é pura e vive em [SpendResolver] (achado ALTO
     * 11/12 da auditoria: essa lógica não tinha teste nenhum). Compartilhado
     * entre o caminho de assinatura local (buildSignedTx, carteira com
     * seed) e o de montar PSBT pra assinatura air-gapped
     * (buildUnsignedPsbtForWatchOnly, carteira watch-only). Não deriva
     * NENHUMA chave (nem pública nem privada) — isso é responsabilidade de
     * cada chamador, já que os dois caminhos derivam de formas diferentes
     * (seed vs. xpub).
     *
     * [seed], quando presente, habilita destino Silent Payments (BIP-352,
     * Fase 1): só o caminho de assinatura local (carteira com seed neste
     * aparelho) consegue calcular o scriptPubKey real de um output SP —
     * o watch-only air-gapped (que chama sem [seed]) recebe erro claro se
     * o destino for um endereço SP, ao invés de tentar e falhar fundo
     * (ver TxAssembler.resolveSilentPaymentDestination pro motivo).
     */
    /**
     * UTXOs Silent Payments confirmados que a rede já gastou de verdade
     * entretanto nunca saíam sozinhos de wallet.spUtxos até esta função
     * existir — a remoção só acontecia (spentSilentPaymentUtxoKeys, ver
     * PreparedTx) num gasto NOVO feito com o app já corrigido. Qualquer
     * UTXO SP gasto ANTES desse fix (ex.: o primeiro teste real em
     * mainnet, 2026-09-03) ficava fantasma pra sempre, reoferecido em
     * todo envio futuro e sempre rejeitado pela rede com
     * "bad-txns-inputs-missingorspent" (bug real, 2026-09-04).
     *
     * IMPORTANTE (bug real corrigido na mesma sessão, mesmo dia): a
     * primeira versão desta função checava via [ChainDataSource.getUtxos]
     * do provedor configurado (que pode ser o node Electrum/Floresta
     * PRÓPRIO do usuário, ver [NodePrefs]) — uma lista vazia por índice
     * incompleto do node (mais novo/menos testado que um Esplora público,
     * ver [[project_floresta_node]]) é indistinguível de "já foi gasto" e
     * podou pelo menos um UTXO SP de verdade que ainda não tinha sido
     * gasto, fazendo o app dizer "sem saldo" por engano (assustador, mas
     * o dinheiro nunca saiu do lugar — só sumiu do rastreamento LOCAL).
     * Corrigido usando [BlockstreamClient.getOutspend] — SEMPRE via
     * Blockstream/mempool.space (nunca o node próprio), pergunta sobre o
     * output EXATO (txid:vout) e responde spent=true/false sem ambiguidade
     * nenhuma, em vez de inferir a partir de uma lista que pode estar
     * incompleta.
     */
    private suspend fun pruneSpentSilentPaymentUtxos(
        spUtxos: List<SilentPaymentsConfirmer.ConfirmedUtxo>,
        network: Network
    ): List<SilentPaymentsConfirmer.ConfirmedUtxo> {
        if (spUtxos.isEmpty()) return spUtxos
        val stillUnspent = mutableListOf<SilentPaymentsConfirmer.ConfirmedUtxo>()
        val spentKeys = mutableSetOf<String>()
        for (u in spUtxos) {
            val spent = try {
                BlockstreamClient.getOutspend(u.txid, u.vout, network)
            } catch (e: Exception) {
                // Falha de rede/provedor nesta checagem específica — não
                // arrisca podar um UTXO de verdade por causa disso, deixa
                // pro próximo envio/sync tentar de novo.
                stillUnspent += u
                continue
            }
            if (spent) spentKeys += "${u.txid}:${u.vout}" else stillUnspent += u
        }
        if (spentKeys.isNotEmpty()) {
            WalletStorage.removeSilentPaymentUtxos(spentKeys)
        }
        return stillUnspent
    }

    private suspend fun resolveSpend(
        wallet: WalletData,
        xpub: String,
        network: Network,
        spendType: SpendType,
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>?,
        seed: ByteArray? = null
    ): SpendResolver.Resolved {
        val scanResult = scanForSend(xpub, network, spendType)
        val dataSource = NodePrefs.dataSource(getApplication())

        // UTXOs Silent Payments (wallet.spUtxos) só entram como candidatos
        // quando: (1) tem seed (watch-only não sabe derivar a chave de
        // gasto SP, isso é a Fase 5) e (2) a carteira é BIP86 — SP é
        // SEMPRE Taproot, e signAndFinalize() ainda assina a tx inteira
        // com UM tipo de witness só; misturar SP com UTXOs BIP84 (SegWit
        // v0) precisaria de um assinador com witness heterogêneo por
        // input, que não existe ainda (ver TxAssembler.deriveSpendableInputs).
        // Poda os que a rede já gastou (ver pruneSpentSilentPaymentUtxos)
        // antes de virarem candidato — fecha o bug do UTXO fantasma.
        val silentPaymentCandidates = if (seed != null && spendType == SpendType.BIP86)
            pruneSpentSilentPaymentUtxos(wallet.spUtxos, network)
        else emptyList()

        // scanResult.totalSats só conta UTXOs normais (BIP84/86 derivados) —
        // achado real: uma carteira só com saldo em UTXOs Silent Payments
        // batia nesse erro mesmo com o UTXO SP selecionado manualmente na
        // tela de envio, porque essa checagem não sabia que esse saldo
        // existia.
        if (scanResult.totalSats + silentPaymentCandidates.sumOf { it.valueSats } == 0L) {
            error("Saldo zero — nada para enviar.")
        }

        // UTXO congelado (tela de UTXOs) nunca entra num envio, automático ou
        // manual — congelar promete proteção na UI, então tem que valer pra
        // qualquer caminho que chega aqui.
        val candidates = SpendResolver.candidatesFrom(scanResult.addressesWithFunds, wallet.frozenUtxoKeys, silentPaymentCandidates)
        if (candidates.isEmpty()) {
            error("Todos os UTXOs disponíveis estão congelados — descongele pelo menos um pra enviar.")
        }

        val chosen = SpendResolver.chooseUtxos(candidates, amountSats, sweep, manualUtxoKeys, feeRateSatPerVbyte, spendType)

        // Confere CADA UTXO que vai ser gasto contra a transação anterior
        // real (não só o que o provedor de saldo/UTXOs reportou) — fecha o
        // vetor de um servidor malicioso/MITM mentir o valor pra inflar a
        // fee às custas do usuário. Ver UtxoValueVerifier. Roda pros dois
        // caminhos de envio (local signing e air-gapped) porque os dois
        // passam por resolveSpend(). (dataSource já resolvido acima.)
        chosen.forEach { UtxoValueVerifier.verify(dataSource, network, it.utxo) }

        val precomputedDestSpk = if (SilentPaymentAddress.looksLikeSilentPaymentAddress(destination)) {
            val s = requireNotNull(seed) {
                "Esta carteira é watch-only (sem seed neste aparelho) — enviar pra um endereço " +
                "Silent Payments ainda exige a carteira com a seed neste dispositivo. Suporte " +
                "air-gapped pra Silent Payments é uma fase futura."
            }
            TxAssembler.resolveSilentPaymentDestination(chosen, destination, s, network, spendType)
        } else null

        return SpendResolver.resolve(chosen, destination, network, amountSats, sweep, feeRateSatPerVbyte, spendType, precomputedDestSpk)
    }

    private suspend fun buildSignedTx(
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>? = null
    ): PreparedTx {
        require(feeRateSatPerVbyte >= 0.5) { "Taxa mínima é 0.5 sat/vB" }

        // Protege a sequência inteira (carregar carteira → derivar → reservar
        // índice de troco → assinar) contra uma troca de carteira ativa no
        // meio do caminho — sem isso, o troco poderia ser reservado contra o
        // índice de OUTRA carteira enquanto a tx é assinada com a seed desta.
        return walletSwitchMutex.withLock {
        val wallet  = WalletStorage.load()
        if (wallet.isWatchOnly) {
            // Sem seed neste dispositivo — assinatura precisa acontecer no
            // aparelho signer, via PSBT (ver prepareAirGappedSend()).
            error("Esta carteira é watch-only (sem seed neste aparelho) — use o fluxo de assinatura air-gapped.")
        }
        val xpub    = requireNotNull(wallet.xpub) { "xpub não encontrado" }
        val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
        val seed    = SeedDerivation.fromMnemonic(wallet.mnemonic!!, wallet.passphrase!!)
        val spendType = wallet.spendType

        val resolved = resolveSpend(wallet, xpub, network, spendType, destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys, seed)

        val spendable = TxAssembler.deriveSpendableInputs(resolved.chosen, seed, network, spendType)

        val txOutputs: List<TxOut> = if (resolved.changeValue != null) {
            // Reserva atômica (load+incrementa+persiste numa seção crítica só) —
            // evita que dois envios concorrentes derivem o mesmo índice de troco.
            val changeIndex = WalletStorage.reserveNextInternalIndex()
            val (changePrivKey, _, changeSpk) = TxAssembler.deriveKeyAndScript(seed, network, spendType, 1, changeIndex)
            try {
                listOf(TxOut(resolved.sendAmount, resolved.destSpk), TxOut(resolved.changeValue, changeSpk))
            } finally {
                changePrivKey.fill(0)
            }
        } else {
            listOf(TxOut(resolved.sendAmount, resolved.destSpk))
        }

        val (rawTxBytes, txid) = TxAssembler.signAndFinalize(spendable, txOutputs, spendType)
        val rawTxHex = rawTxBytes.joinToString("") { "%02x".format(it) }

        val spentSpKeys = resolved.chosen
            .filter { it.silentPaymentTweak != null }
            .mapTo(mutableSetOf()) { "${it.utxo.txid}:${it.utxo.vout}" }

        PreparedTx(
            rawTxHex = rawTxHex, txid = txid, seed = seed, network = network,
            spentSilentPaymentUtxoKeys = spentSpKeys,
            totalInputSats = resolved.chosen.sumOf { it.utxo.valueSats }
        )
        }
    }

    // =====================================================================
    // Fluxo air-gapped (Fase C4/C5) — watch-only monta o PSBT, dispositivo
    // com a seed assina, watch-only confere e transmite. Ver plano
    // /home/felipe/.claude/plans/wondrous-seeking-waffle.md.
    // =====================================================================

    /** Lado watch-only: monta o PSBT não-assinado pra mostrar como QR. */
    fun prepareAirGappedSend(
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>? = null
    ) {
        _airGappedSendState.value = AirGappedSendState.Building
        viewModelScope.launch {
            try {
                val psbt = withContext(Dispatchers.IO) {
                    buildUnsignedPsbtForWatchOnly(destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)
                }
                _airGappedSendState.value = AirGappedSendState.Ready(psbt)
            } catch (e: Exception) {
                _airGappedSendState.value = AirGappedSendState.Error(humanizeError(e))
            }
        }
    }

    fun resetAirGappedSendState() { _airGappedSendState.value = AirGappedSendState.Idle }

    private suspend fun buildUnsignedPsbtForWatchOnly(
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>?
    ): AirGappedPsbt {
        require(feeRateSatPerVbyte >= 0.5) { "Taxa mínima é 0.5 sat/vB" }

        return walletSwitchMutex.withLock {
        val wallet = WalletStorage.load()
        require(wallet.isWatchOnly) { "Esta função é só para carteira watch-only." }
        val xpub    = requireNotNull(wallet.xpub) { "xpub não encontrado" }
        val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
        val spendType = wallet.spendType
        // Fingerprint "desconhecido" (zeros, convenção BIP174) quando a
        // carteira foi importada por xpub pura — wallet.fingerprint nesse
        // caso é só um pseudo-ID interno (diretório/registro), nunca deve
        // ir dentro do PSBT (o lado assinante rejeitaria por não bater).
        val masterFingerprint = if (wallet.hasVerifiedFingerprint) wallet.fingerprint.hexToBytes() else ByteArray(4)

        val resolved = resolveSpend(wallet, xpub, network, spendType, destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)

        // Reserva atômica — mesma proteção de buildSignedTx() contra dois
        // envios concorrentes derivarem o mesmo índice de troco.
        val changeIndex = if (resolved.changeValue != null) WalletStorage.reserveNextInternalIndex() else null

        val built = PsbtAssembler.build(
            chosen            = resolved.chosen,
            destSpk           = resolved.destSpk,
            sendAmount        = resolved.sendAmount,
            changeValue       = resolved.changeValue,
            changeIndex       = changeIndex,
            xpub              = xpub,
            network           = network,
            spendType         = spendType,
            masterFingerprint = masterFingerprint
        )

        AirGappedPsbt(psbtBase64 = built.psbtBase64, expectedTxid = built.expectedTxid, network = network)
        }
    }

    /** Lado watch-only: recebeu a tx assinada de volta (QR) — confere o
     *  txid ANTES de transmitir (ver nota de segurança em parseSignedTxidHex()).
     *  [mode] escolhe entre Internet direto (BlockstreamClient) ou BitChat/
     *  Nostr (mesmo canal/bot do envio normal) — watch-only não tem seed
     *  pra derivar a identidade Nostr NIP-06 de sempre, então esse caminho
     *  usa NostrKeys.random() (chave efêmera, nunca persistida). */
    fun submitSignedAirGappedTx(rawTxHex: String, expectedTxid: String, network: Network, mode: SendMode = SendMode.Internet) {
        _airGappedBroadcastState.value = AirGappedBroadcastState.Verifying
        viewModelScope.launch {
            try {
                val actualTxid = withContext(Dispatchers.IO) { parseSignedTxidHex(rawTxHex) }
                if (actualTxid != expectedTxid) {
                    _airGappedBroadcastState.value = AirGappedBroadcastState.Error(
                        "A tx assinada não bate com o que foi pedido pra assinar (txid esperado $expectedTxid, recebido $actualTxid) — NÃO transmitida. Confira o QR e tente de novo."
                    )
                    return@launch
                }
                val broadcastTxid = withContext(Dispatchers.IO) {
                    when (mode) {
                        is SendMode.Internet -> NodePrefs.dataSource(getApplication()).broadcast(rawTxHex, network)
                        is SendMode.Tor      -> try {
                            BlockstreamClient.broadcastViaProxy(rawTxHex, network, TorPrefs.proxy(getApplication()))
                        } catch (e: Exception) {
                            throw RuntimeException(
                                "Não foi possível transmitir via Tor — confira se o Orbot está instalado, " +
                                    "rodando, e com o proxy SOCKS habilitado em " +
                                    "${TorPrefs.getHost(getApplication())}:${TorPrefs.getPort(getApplication())}. (${e.message})",
                                e
                            )
                        }
                        is SendMode.BitChat  -> broadcastAirGappedViaNostr(rawTxHex, expectedTxid)
                    }
                }
                _airGappedBroadcastState.value = AirGappedBroadcastState.Success(broadcastTxid)
                doScan()
            } catch (e: Exception) {
                _airGappedBroadcastState.value = AirGappedBroadcastState.Error(humanizeError(e))
            }
        }
    }

    /** Publica a tx já assinada (air-gapped, watch-only) no canal BitChat/
     *  Nostr — mesmo bot/geohash de executeSendViaNostr(), identidade
     *  EFÊMERA (NostrKeys.random()) já que não há seed neste aparelho. */
    private suspend fun broadcastAirGappedViaNostr(rawTxHex: String, expectedTxid: String): String {
        val (nostrPrivKey, nostrPubKey) = NostrKeys.random()
        val relays = GeoRelayDirectory.closestRelays(BITCHAT_GEOHASH)
        val event = try {
            NostrEvent.build(
                privKey32 = nostrPrivKey,
                pubKey32  = nostrPubKey,
                kind      = 20000,
                tags      = listOf(listOf("g", BITCHAT_GEOHASH)),
                content   = "!broadcast $rawTxHex"
            )
        } finally {
            nostrPrivKey.fill(0)
        }

        val result = NostrRelayClient.publishAndAwaitReply(
            event                  = event,
            relays                 = relays,
            ourPubkeyHex           = event.pubkey,
            geohash                = BITCHAT_GEOHASH,
            timeoutMs              = 18_000L,
            expectedReplyPubkeyHex = BITCHAT_BROADCASTER_PUBKEY_HEX
        ) { content -> content.contains(expectedTxid, ignoreCase = true) }

        if (!result.published) {
            error("Não foi possível publicar via Nostr — nenhum relay confirmou o recebimento.")
        }

        return expectedTxid
    }

    fun resetAirGappedBroadcastState() { _airGappedBroadcastState.value = AirGappedBroadcastState.Idle }

    /** Lado signer: assina um PSBT escaneado com a seed da carteira ativa. */
    fun signAirGappedPsbt(psbtBase64: String) {
        _airGappedSignState.value = AirGappedSignState.Signing
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    walletSwitchMutex.withLock {
                        val wallet = WalletStorage.load()
                        require(!wallet.isWatchOnly) { "Esta carteira não tem seed — não pode assinar." }
                        val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
                        val seed = SeedDerivation.fromMnemonic(wallet.mnemonic!!, wallet.passphrase!!)
                        try {
                            when (wallet.spendType) {
                                SpendType.BIP84 -> AirGappedPsbtSigner.signSegwit(psbtBase64, seed, wallet, network)
                                SpendType.BIP86 -> AirGappedPsbtSigner.signTaproot(psbtBase64, seed, wallet, network)
                            }
                        } finally {
                            seed.fill(0)
                        }
                    }
                }
                _airGappedSignState.value = AirGappedSignState.Success(result.first, result.second)
            } catch (e: Exception) {
                _airGappedSignState.value = AirGappedSignState.Error(humanizeError(e))
            }
        }
    }

    fun resetAirGappedSignState() { _airGappedSignState.value = AirGappedSignState.Idle }

    private fun humanizeError(e: Exception): String {
        val msg = e.message ?: "Erro desconhecido"
        return when {
            // Checa ANTES do catch-all genérico de "connect"/"network" logo
            // abaixo — a mensagem de executeSendViaTor() já é específica
            // (diz pra checar o Orbot) e o texto da exceção subjacente
            // (ex: "Connection refused") contém "connect" como substring,
            // o que cairia no genérico e esconderia a orientação certa.
            msg.contains("Orbot", ignoreCase = true) -> msg
            msg.contains("RIPEMD160", ignoreCase = true) ->
                "Erro ao inicializar criptografia. Reinicie o app e tente novamente."
            msg.contains("429") || msg.contains("Too Many Requests", ignoreCase = true) ->
                "Limite de requisições do servidor atingido (comum se você atualizar manualmente " +
                "várias vezes seguidas) — o app espera automaticamente mais tempo antes de tentar " +
                "de novo. Saldo mostrado é o último confirmado, não uma perda.\n\nDetalhe técnico: $msg"
            msg.contains("network", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("connect", ignoreCase = true) ->
                "Sem conexão com a rede. Verifique seu internet e tente novamente."
            msg.contains("xpub", ignoreCase = true) ->
                "Dados da wallet corrompidos. Tente restaurar a partir do mnemonic."
            else -> msg
        }
    }

    private fun addressToScriptPubKey(address: String, network: Network): ByteArray =
        AddressCodec.addressToScriptPubKey(address, network)

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
