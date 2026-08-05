package com.pokewallet.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokewallet.crypto.*
import com.pokewallet.network.BlockstreamClient
import com.pokewallet.network.WalletScanner
import com.pokewallet.nostr.GeoRelayDirectory
import com.pokewallet.nostr.NostrEvent
import com.pokewallet.nostr.NostrKeys
import com.pokewallet.nostr.NostrRelayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        val isWatchOnly: Boolean = false
    ) : WalletState()
    data class Error(val message: String) : WalletState()
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
 * Por quanto tempo o resultado do último scan (doScan()) é reaproveitado
 * por buildSignedTx() em vez de disparar um scan completo novo. 90s = 1.5x
 * o intervalo do autoScanJob (60s) — cobre o caso comum (enviar logo após
 * a varredura periódica) sem arriscar UTXO desatualizado por muito tempo.
 */
private const val SCAN_CACHE_TTL_MS = 90_000L

/** Extrai o nome do Pokémon de uma passphrase no formato "pokemon:N:Nome". */
private val POKEMON_PASSPHRASE_REGEX = Regex("^pokemon:\\d+:(.+)$")

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val _walletState = MutableStateFlow<WalletState>(WalletState.Loading)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _priceState = MutableStateFlow<BlockstreamClient.BtcPrices?>(null)
    val priceState: StateFlow<BlockstreamClient.BtcPrices?> = _priceState.asStateFlow()

    private val _feeState = MutableStateFlow<BlockstreamClient.FeeEstimates?>(null)
    val feeState: StateFlow<BlockstreamClient.FeeEstimates?> = _feeState.asStateFlow()

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
                _walletState.value = WalletState.Loaded(
                    fingerprint  = wallet.fingerprint,
                    walletName   = wallet.walletName,
                    displayName  = displayName,
                    network      = wallet.network,
                    balanceSats  = null,
                    pendingSats  = null,
                    utxoCount    = null,
                    isScanning   = false,
                    scanStatus   = null,
                    lastScanTime = null,
                    isWatchOnly  = wallet.isWatchOnly
                )
                startAutoScan()
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
        lastScanResult = null
        lastScanResultAtMs = 0L
        lastKnownPendingSats = 0L
        _txHistory.value = emptyList()
        _feeState.value = null
    }

    private fun startAutoScan() {
        autoScanJob?.cancel()
        loadPrice()
        loadFees()
        autoScanJob = viewModelScope.launch {
            doScan()
            while (true) {
                delay(60_000L)
                doScan()
                loadPrice()
                loadFees()
            }
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
                val fees    = withContext(Dispatchers.IO) { BlockstreamClient.getFeeEstimates(network) }
                _feeState.value = fees
            } catch (_: Exception) {
                if (_feeState.value == null) _feeState.value = BlockstreamClient.FeeEstimates.FALLBACK
            }
        }
    }

    /** Taxa sugerida (prioridade alta / confirmação mais rápida) pra pré-popular a UI de envio. */
    fun getCurrentFeeEstimates(): BlockstreamClient.FeeEstimates =
        _feeState.value ?: BlockstreamClient.FeeEstimates.FALLBACK

    /** Força uma varredura imediata (sem esperar o ciclo de 60s) — usado pelo
     *  botão Home, além do refresh automático já rodando em startAutoScan(). */
    fun refreshNow() {
        viewModelScope.launch { doScan() }
    }

    private fun loadTxHistory(addresses: List<com.pokewallet.network.WalletScanner.ScannedAddress>, network: Network) {
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

            val result = withContext(Dispatchers.IO) {
                WalletScanner.scan(
                    xpub       = xpub,
                    network    = network,
                    spendType  = wallet.spendType,
                    onProgress = { _, index, _ ->
                        val loaded = _walletState.value as? WalletState.Loaded ?: return@scan
                        _walletState.value = loaded.copy(scanStatus = "Verificando endereço $index…")
                    }
                )
            }

            withContext(Dispatchers.IO) {
                wallet.nextExternalIndex = result.nextExternalIndex
                wallet.nextInternalIndex = result.nextInternalIndex
                WalletStorage.save(wallet)
            }

            lastScanResult = result
            lastScanResultAtMs = System.currentTimeMillis()

            val confirmedSats = result.addressesWithFunds.sumOf { it.stats.confirmedSats }
            val pendingSats   = result.addressesWithFunds.sumOf { it.stats.pendingSats }

            if (pendingSats > 0L && lastKnownPendingSats == 0L) {
                _pendingTxEvent.emit(pendingSats)
            }
            lastKnownPendingSats = pendingSats

            _walletState.value = current.copy(
                balanceSats  = confirmedSats,
                pendingSats  = if (pendingSats != 0L) pendingSats else null,
                utxoCount    = result.addressesWithFunds.sumOf { it.utxos.size },
                isScanning   = false,
                scanStatus   = null,
                lastScanTime = Date()
            )

            loadTxHistory(result.allWithActivity, network)
        } catch (e: Exception) {
            val fallback = _walletState.value as? WalletState.Loaded ?: current
            _walletState.value = fallback.copy(isScanning = false, scanStatus = null)
        }
    }

    fun createWallet(
        network: Network = Network.MAINNET,
        passphraseMode: PassphraseMode = PassphraseMode.Pokemon,
        wordCount: Int = 24,
        spendType: SpendType = SpendType.BIP84,
        customName: String? = null
    ) {
        _walletState.value = WalletState.Creating
        val context = getApplication<Application>()
        viewModelScope.launch {
            walletSwitchMutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        createWalletIntoPendingSlot(context) {
                            WalletInit.run(network, passphraseMode, wordCount, spendType)
                        }
                    }
                    val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                    WalletRegistry.setDisplayName(
                        context, wallet.fingerprint,
                        resolveDisplayName(wallet.fingerprint, customName, wallet.passphrase)
                    )
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

    /** Lista (fingerprint, nome de exibição) de todas as carteiras conhecidas no disco. */
    fun listKnownWallets(): List<Pair<String, String>> {
        val context = getApplication<Application>()
        return WalletRegistry.listKnownWalletIds(context.filesDir)
            .map { id -> id to WalletRegistry.displayNameOrFallback(context, id) }
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
     * "[fingerprint/purpose'/coin'/0']xpub" da carteira ativa — a mesma
     * string que dá pra colar (ou, quando o QR scanner for adicionado,
     * escanear) na tela de importação watch-only de outro aparelho.
     * Nenhum dado secreto: é só a chave pública, segura de exportar.
     */
    fun getAccountOrigin(): String? {
        if (!walletSwitchMutex.tryLock()) return null
        return try {
            WalletStorage.load().accountOrigin
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
    fun getUtxoList(): List<UtxoRow>? {
        if (!walletSwitchMutex.tryLock()) return null
        return try {
            val wallet = WalletStorage.load()
            val scan = lastScanResult ?: return emptyList()
            scan.addressesWithFunds.flatMap { addr ->
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
            }.sortedByDescending { it.valueSats }
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
     *  WalletRestore.run). Nome de exibição sem Pokémon (não há passphrase). */
    fun importWatchOnly(
        accountOrigin: String,
        network: Network,
        spendType: SpendType,
        customName: String? = null
    ) {
        _watchOnlyImportState.value = WatchOnlyImportState.Importing
        val context = getApplication<Application>()
        viewModelScope.launch {
            var imported = false
            walletSwitchMutex.withLock {
                try {
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
        val network: Network
    )

    private suspend fun executeSend(
        destination: String, amountSats: Long?, sweep: Boolean, feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>? = null
    ): String {
        val prepared = buildSignedTx(destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)
        try {
            return BlockstreamClient.broadcast(prepared.rawTxHex, prepared.network)
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

        val (nostrPrivKey, nostrPubKey) = NostrKeys.deriveFromSeed(prepared.seed)
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
            event        = event,
            relays       = relays,
            ourPubkeyHex = event.pubkey,
            geohash      = BITCHAT_GEOHASH,
            timeoutMs    = 18_000L
        ) { content -> content.contains(prepared.txid, ignoreCase = true) }

        if (!result.published) {
            error("Não foi possível publicar via Nostr — nenhum relay confirmou o recebimento.")
        }

        return NostrSendResult(
            txid      = prepared.txid,
            confirmed = result.replyContent != null,
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

        val result = WalletScanner.scan(xpub = xpub, network = network, spendType = spendType)
        lastScanResult = result
        lastScanResultAtMs = System.currentTimeMillis()
        return result
    }

    /** Um UTXO candidato a ser gasto, com o endereço (chain/index) que o controla. */
    private class SpendCandidate(val addr: com.pokewallet.network.WalletScanner.ScannedAddress, val utxo: BlockstreamClient.Utxo)

    private class ResolvedSpend(
        val chosen: List<SpendCandidate>,
        val destSpk: ByteArray,
        val sendAmount: Long,
        val changeValue: Long?
    )

    /**
     * Escaneia (ou reusa o cache), filtra congelados e resolve QUAIS UTXOs
     * entram na tx (manual > sweep > CoinSelector automático) + quanto vai
     * pro destino/troco — compartilhado entre o caminho de assinatura local
     * (buildSignedTx, carteira com seed) e o de montar PSBT pra assinatura
     * air-gapped (buildUnsignedPsbtForWatchOnly, carteira watch-only). Não
     * deriva NENHUMA chave (nem pública nem privada) — isso é responsabilidade
     * de cada chamador, já que os dois caminhos derivam de formas diferentes
     * (seed vs. xpub).
     */
    private suspend fun resolveSpend(
        wallet: WalletData,
        xpub: String,
        network: Network,
        spendType: SpendType,
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double,
        manualUtxoKeys: Set<String>?
    ): ResolvedSpend {
        val scanResult = scanForSend(xpub, network, spendType)
        if (scanResult.totalSats == 0L) error("Saldo zero — nada para enviar.")

        // UTXO congelado (tela de UTXOs) nunca entra num envio, automático ou
        // manual — congelar promete proteção na UI, então tem que valer pra
        // qualquer caminho que chega aqui.
        val candidates = scanResult.addressesWithFunds.flatMap { addr ->
            addr.utxos
                .filterNot { wallet.frozenUtxoKeys.contains("${it.txid}:${it.vout}") }
                .map { SpendCandidate(addr, it) }
        }
        if (candidates.isEmpty()) {
            error("Todos os UTXOs disponíveis estão congelados — descongele pelo menos um pra enviar.")
        }

        // Sweep continua gastando TUDO que não estiver congelado (é a
        // definição de sweep). Envio parcial seleciona de verdade via
        // CoinSelector em vez de sempre consolidar a carteira inteira.
        val chosen: List<SpendCandidate> = if (manualUtxoKeys != null) {
            // Seleção manual (Fase B3): usa EXATAMENTE os UTXOs escolhidos na
            // tela de UTXOs — nunca completa com outros automaticamente, isso
            // anularia o propósito de escolher UTXOs específicos (privacidade/
            // organização). Sweep + seleção manual = varre só os selecionados.
            val byKey = candidates.associateBy { "${it.utxo.txid}:${it.utxo.vout}" }
            val missing = manualUtxoKeys.filterNot { byKey.containsKey(it) }
            if (missing.isNotEmpty()) {
                error("Um ou mais UTXOs selecionados não estão mais disponíveis (gasto ou congelado nesse meio-tempo) — atualize a seleção e tente de novo.")
            }
            val manualChosen = manualUtxoKeys.map { byKey.getValue(it) }
            if (!sweep) {
                val targetValue = amountSats ?: error("Valor não informado")
                val totalSelected = manualChosen.sumOf { it.utxo.valueSats }
                val estimatedFee = FeeEstimator.estimateFee(manualChosen.size, 2, spendType, feeRateSatPerVbyte)
                require(totalSelected >= targetValue + estimatedFee) {
                    "Os UTXOs selecionados ($totalSelected sat) não cobrem o valor + taxa estimada (~${targetValue + estimatedFee} sat) — selecione mais UTXOs."
                }
            }
            manualChosen
        } else if (sweep) {
            candidates
        } else {
            val targetValue = amountSats ?: error("Valor não informado")
            val coinUtxos = candidates.map { c ->
                Utxo(
                    txid         = hexToBytes(c.utxo.txid),
                    vout         = c.utxo.vout,
                    value        = c.utxo.valueSats,
                    scriptPubKey = byteArrayOf(),
                    chain        = c.addr.chain,
                    index        = c.addr.index
                )
            }
            val (selected, _) = CoinSelector.select(coinUtxos, targetValue, feeRateSatPerVbyte, spendType)
            val selectedRefs = java.util.IdentityHashMap<Utxo, Unit>()
            selected.forEach { selectedRefs[it] = Unit }
            candidates.filterIndexed { i, _ -> selectedRefs.containsKey(coinUtxos[i]) }
        }

        val totalInputSats = chosen.sumOf { it.utxo.valueSats }
        val destSpk = addressToScriptPubKey(destination, network)

        val plan = ChangePlanner.plan(
            totalInputSats     = totalInputSats,
            requestedAmount    = amountSats,
            sweep              = sweep,
            inputCount         = chosen.size,
            feeRateSatPerVbyte = feeRateSatPerVbyte,
            spendType          = spendType
        )

        return ResolvedSpend(chosen, destSpk, plan.sendAmount, plan.changeValue)
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

        val resolved = resolveSpend(wallet, xpub, network, spendType, destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)

        data class SpendableUtxo(
            val txidLE: ByteArray, val vout: Int, val valueSats: Long,
            val scriptPubKey: ByteArray, val privateKey: ByteArray, val pubKey: ByteArray
        )

        // Deriva a chave uma vez por endereço (chain,index) mesmo que ele
        // tenha múltiplos UTXOs escolhidos — evita derivação repetida.
        val keyCache = mutableMapOf<Pair<Int, Int>, Triple<ByteArray, ByteArray, ByteArray>>()
        val spendable = mutableListOf<SpendableUtxo>()
        for (c in resolved.chosen) {
            val keyId = c.addr.chain to c.addr.index
            val (privKey, pubKey, spk) = keyCache.getOrPut(keyId) {
                val hdKey = when (spendType) {
                    SpendType.BIP84 -> KeyDerivation.bip84(seed, coin = network.coinType, account = 0,
                        change = c.addr.chain, address = c.addr.index)
                    SpendType.BIP86 -> KeyDerivation.bip86(seed, coin = network.coinType, account = 0,
                        change = c.addr.chain, address = c.addr.index)
                }
                val pk  = hdKey.privateKey
                val pub = Secp256k1.publicKeyFromPrivate(pk)
                val script = when (spendType) {
                    SpendType.BIP84 -> byteArrayOf(0x00, 0x14) + Hashes.hash160(pub)
                    SpendType.BIP86 -> {
                        val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(pk)
                        byteArrayOf(0x51, 0x20) + Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
                    }
                }
                Triple(pk, pub, script)
            }
            spendable += SpendableUtxo(
                txidLE       = hexToBytes(c.utxo.txid).reversedArray(),
                vout         = c.utxo.vout,
                valueSats    = c.utxo.valueSats,
                scriptPubKey = spk,
                privateKey   = privKey,
                pubKey       = pubKey
            )
        }

        val txOutputs: List<TxOut> = if (resolved.changeValue != null) {
            // Reserva atômica (load+incrementa+persiste numa seção crítica só) —
            // evita que dois envios concorrentes derivem o mesmo índice de troco.
            val changeIndex = WalletStorage.reserveNextInternalIndex()
            val changeHdKey = when (spendType) {
                SpendType.BIP84 -> KeyDerivation.bip84(seed, coin = network.coinType, account = 0,
                    change = 1, address = changeIndex)
                SpendType.BIP86 -> KeyDerivation.bip86(seed, coin = network.coinType, account = 0,
                    change = 1, address = changeIndex)
            }
            val changePubKey = Secp256k1.publicKeyFromPrivate(changeHdKey.privateKey)
            val changeSpk = when (spendType) {
                SpendType.BIP84 -> byteArrayOf(0x00, 0x14) + Hashes.hash160(changePubKey)
                SpendType.BIP86 -> byteArrayOf(0x51, 0x20) + Secp256k1.taprootOutputKeyFromInternalXOnly(
                    Secp256k1.xOnlyPublicKeyFromPrivate(changeHdKey.privateKey))
            }
            changeHdKey.privateKey.fill(0)

            listOf(TxOut(resolved.sendAmount, resolved.destSpk), TxOut(resolved.changeValue, changeSpk))
        } else {
            listOf(TxOut(resolved.sendAmount, resolved.destSpk))
        }

        val txInputs   = spendable.map { s ->
            TxIn(prevTxId = s.txidLE, prevIndex = s.vout, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)
        }
        val unsignedTx = UnsignedTransaction(version = 2, inputs = txInputs, outputs = txOutputs, lockTime = 0L)

        val (rawTxBytes, txid) = when (spendType) {

            SpendType.BIP84 -> {
                val psbt = Psbt(
                    unsignedTx = unsignedTx,
                    inputs     = MutableList(txInputs.size) { PsbtInput() },
                    outputs    = MutableList(txOutputs.size) { PsbtOutput() }
                )

                spendable.forEachIndexed { i, s ->
                    val sig = SegwitSigner.sign(
                        unsignedTx   = unsignedTx,
                        inputIndex   = i,
                        utxoValue    = s.valueSats,
                        scriptPubKey = s.scriptPubKey,
                        privateKey   = s.privateKey
                    )
                    psbt.inputs[i].witnessUtxo = TxOut(s.valueSats, s.scriptPubKey)
                    psbt.inputs[i].partialSignatures[s.pubKey.toHex()] = sig
                }
                spendable.forEach { it.privateKey.fill(0) }

                Pair(psbt.finalize(), psbt.txid())
            }

            SpendType.BIP86 -> {
                val psbt = PsbtTaproot(
                    unsignedTx = unsignedTx,
                    inputs     = MutableList(txInputs.size) { TaprootPsbtInput() },
                    outputs    = MutableList(txOutputs.size) { PsbtOutput() }
                )

                val utxoTxOuts = spendable.map { TxOut(it.valueSats, it.scriptPubKey) }

                spendable.forEachIndexed { i, s ->
                    psbt.inputs[i].witnessUtxo = TxOut(s.valueSats, s.scriptPubKey)

                    val sighash = TaprootSighashCalculator.calculate(
                        tx       = unsignedTx,
                        inputIndex = i,
                        utxos    = utxoTxOuts
                    )
                    val tweakedPrivKey = Secp256k1.taprootTweakPrivateKey(s.privateKey)
                    try {
                        psbt.inputs[i].tapKeySig = SchnorrSigner.sign(
                            msg32     = sighash,
                            privKey32 = tweakedPrivKey
                        )
                    } finally {
                        tweakedPrivKey.fill(0)
                    }
                }
                spendable.forEach { it.privateKey.fill(0) }

                Pair(psbt.finalize(), unsignedTx.txid())
            }
        }

        val rawTxHex = rawTxBytes.joinToString("") { "%02x".format(it) }

        PreparedTx(rawTxHex = rawTxHex, txid = txid, seed = seed, network = network)
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
        val masterFingerprint = wallet.fingerprint.hexToBytes()
        val purpose = spendType.bipPurpose()

        val resolved = resolveSpend(wallet, xpub, network, spendType, destination, amountSats, sweep, feeRateSatPerVbyte, manualUtxoKeys)

        fun deriveKeyAndScript(chain: Int, index: Int): Pair<ByteArray, ByteArray> {
            val accountKey = com.pokewallet.network.XpubAddressDeriver.decodeXpub(xpub)
            val chainKey   = com.pokewallet.network.XpubAddressDeriver.derivePublicChild(accountKey, chain)
            val indexKey   = com.pokewallet.network.XpubAddressDeriver.derivePublicChild(chainKey, index)
            val spk = when (spendType) {
                SpendType.BIP84 -> byteArrayOf(0x00, 0x14) + Hashes.hash160(indexKey.pubKey)
                SpendType.BIP86 -> {
                    val xOnly = indexKey.pubKey.copyOfRange(1, 33)
                    byteArrayOf(0x51, 0x20) + Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
                }
            }
            return indexKey.pubKey to spk
        }

        fun derivationPath(chain: Int, index: Int) = listOf(
            KeyDerivation.hardened(purpose), KeyDerivation.hardened(network.coinType), KeyDerivation.hardened(0), chain, index
        )

        val txInputs = mutableListOf<TxIn>()
        // pubKey, scriptPubKey, (chain, index) — um por input, na mesma ordem de resolved.chosen
        val inputMeta = mutableListOf<Triple<ByteArray, ByteArray, Pair<Int, Int>>>()
        for (c in resolved.chosen) {
            txInputs += TxIn(
                prevTxId = hexToBytes(c.utxo.txid).reversedArray(),
                prevIndex = c.utxo.vout,
                scriptSig = byteArrayOf(),
                sequence = 0xFFFFFFFFL
            )
            val (pubKey, spk) = deriveKeyAndScript(c.addr.chain, c.addr.index)
            inputMeta += Triple(pubKey, spk, c.addr.chain to c.addr.index)
        }

        var changePubKey: ByteArray? = null
        var changeChainIndex: Pair<Int, Int>? = null
        val txOutputs: List<TxOut> = if (resolved.changeValue != null) {
            // Reserva atômica — mesma proteção de buildSignedTx() contra dois
            // envios concorrentes derivarem o mesmo índice de troco.
            val changeIndex = WalletStorage.reserveNextInternalIndex()
            val (pubKey, spk) = deriveKeyAndScript(1, changeIndex)
            changePubKey = pubKey
            changeChainIndex = 1 to changeIndex
            listOf(TxOut(resolved.sendAmount, resolved.destSpk), TxOut(resolved.changeValue, spk))
        } else {
            listOf(TxOut(resolved.sendAmount, resolved.destSpk))
        }

        val unsignedTx = UnsignedTransaction(version = 2, inputs = txInputs, outputs = txOutputs, lockTime = 0L)
        val expectedTxid = unsignedTx.txid()

        val psbtBase64 = when (spendType) {
            SpendType.BIP84 -> {
                val psbt = Psbt(
                    unsignedTx = unsignedTx,
                    inputs     = MutableList(txInputs.size) { PsbtInput() },
                    outputs    = MutableList(txOutputs.size) { PsbtOutput() }
                )
                inputMeta.forEachIndexed { i, (pubKey, spk, chainIndex) ->
                    psbt.inputs[i].witnessUtxo = TxOut(resolved.chosen[i].utxo.valueSats, spk)
                    psbt.inputs[i].bip32Derivations[pubKey.toHex()] =
                        Bip32Derivation(masterFingerprint, derivationPath(chainIndex.first, chainIndex.second))
                }
                if (changePubKey != null && changeChainIndex != null) {
                    psbt.outputs[1].bip32Derivations[changePubKey.toHex()] =
                        Bip32Derivation(masterFingerprint, derivationPath(changeChainIndex.first, changeChainIndex.second))
                }
                psbt.serializeBase64()
            }
            SpendType.BIP86 -> {
                val psbt = PsbtTaproot(
                    unsignedTx = unsignedTx,
                    inputs     = MutableList(txInputs.size) { TaprootPsbtInput() },
                    outputs    = MutableList(txOutputs.size) { PsbtOutput() }
                )
                inputMeta.forEachIndexed { i, (pubKey, spk, chainIndex) ->
                    val xOnly = pubKey.copyOfRange(1, 33)
                    psbt.inputs[i].witnessUtxo = TxOut(resolved.chosen[i].utxo.valueSats, spk)
                    psbt.inputs[i].tapInternalKey = xOnly
                    psbt.inputs[i].tapBip32Derivation =
                        TapBip32Derivation(emptyList(), masterFingerprint, derivationPath(chainIndex.first, chainIndex.second))
                }
                if (changePubKey != null && changeChainIndex != null) {
                    val xOnly = changePubKey.copyOfRange(1, 33)
                    psbt.outputs[1].tapBip32Derivations[xOnly.toHex()] =
                        TapBip32Derivation(emptyList(), masterFingerprint, derivationPath(changeChainIndex.first, changeChainIndex.second))
                }
                psbt.serializeBase64()
            }
        }

        AirGappedPsbt(psbtBase64 = psbtBase64, expectedTxid = expectedTxid, network = network)
        }
    }

    /** Lado watch-only: recebeu a tx assinada de volta (QR) — confere o
     *  txid ANTES de transmitir (ver nota de segurança em parseSignedTxidHex()). */
    fun submitSignedAirGappedTx(rawTxHex: String, expectedTxid: String, network: Network) {
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
                val broadcastTxid = withContext(Dispatchers.IO) { BlockstreamClient.broadcast(rawTxHex, network) }
                _airGappedBroadcastState.value = AirGappedBroadcastState.Success(broadcastTxid)
                doScan()
            } catch (e: Exception) {
                _airGappedBroadcastState.value = AirGappedBroadcastState.Error(humanizeError(e))
            }
        }
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
                                SpendType.BIP84 -> signSegwitPsbt(psbtBase64, seed, wallet, network)
                                SpendType.BIP86 -> signTaprootPsbt(psbtBase64, seed, wallet, network)
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

    /** (rawTxHex, txid) do PSBT SegWit v0 assinado com a chave certa por input. */
    private fun signSegwitPsbt(psbtBase64: String, seed: ByteArray, wallet: WalletData, network: Network): Pair<String, String> {
        val psbt = try {
            Psbt.parseBase64(psbtBase64)
        } catch (e: Exception) {
            error("PSBT inválido ou corrompido: ${e.message}")
        }
        require(psbt.unsignedTx.inputs.isNotEmpty()) { "PSBT sem inputs" }

        psbt.inputs.forEachIndexed { i, input ->
            val utxo = input.witnessUtxo ?: error("Input $i sem witness_utxo — PSBT incompleto")
            val (pubKeyHex, deriv) = input.bip32Derivations.entries.firstOrNull()
                ?: error("Input $i sem informação de derivação — não sei qual chave usar pra assinar")
            require(deriv.masterFingerprint.toHex() == wallet.fingerprint) {
                "Esse PSBT foi montado por outra carteira (fingerprint ${deriv.masterFingerprint.toHex()} ≠ ${wallet.fingerprint} desta)."
            }
            require(deriv.path.size == 5) { "Caminho de derivação inesperado no input $i" }
            val chain = deriv.path[3]
            val index = deriv.path[4]

            val hdKey = KeyDerivation.bip84(seed, coin = network.coinType, account = 0, change = chain, address = index)
            try {
                val pubKey = Secp256k1.publicKeyFromPrivate(hdKey.privateKey)
                require(pubKey.toHex() == pubKeyHex) {
                    "Pubkey derivada não bate com a do PSBT no input $i — dado corrompido ou adulterado, assinatura recusada."
                }
                // Recalcula o scriptPubKey a partir da CHAVE DERIVADA LOCALMENTE (não confia
                // cegamente no scriptPubKey que veio no PSBT) — se não bater, o PSBT está
                // pedindo pra assinar algo que não corresponde à chave que ele mesmo declarou.
                val expectedSpk = byteArrayOf(0x00, 0x14) + Hashes.hash160(pubKey)
                require(expectedSpk.contentEquals(utxo.scriptPubKey)) {
                    "scriptPubKey do input $i não bate com o esperado pra essa chave — assinatura recusada."
                }

                val sig = SegwitSigner.sign(
                    unsignedTx   = psbt.unsignedTx,
                    inputIndex   = i,
                    utxoValue    = utxo.value,
                    scriptPubKey = utxo.scriptPubKey,
                    privateKey   = hdKey.privateKey
                )
                psbt.inputs[i].partialSignatures[pubKeyHex] = sig
            } finally {
                hdKey.privateKey.fill(0)
            }
        }

        val rawTxBytes = psbt.finalize()
        return rawTxBytes.joinToString("") { "%02x".format(it) } to psbt.txid()
    }

    /** (rawTxHex, txid) do PSBT Taproot assinado com a chave certa por input. */
    private fun signTaprootPsbt(psbtBase64: String, seed: ByteArray, wallet: WalletData, network: Network): Pair<String, String> {
        val psbt = try {
            PsbtTaproot.parseBase64(psbtBase64)
        } catch (e: Exception) {
            error("PSBT inválido ou corrompido: ${e.message}")
        }
        require(psbt.unsignedTx.inputs.isNotEmpty()) { "PSBT sem inputs" }

        val allUtxos = psbt.inputs.mapIndexed { i, input ->
            input.witnessUtxo ?: error("Input $i sem witness_utxo — necessário em todos os inputs pro cálculo do sighash Taproot")
        }

        psbt.inputs.forEachIndexed { i, input ->
            val utxo = allUtxos[i]
            val deriv = input.tapBip32Derivation
                ?: error("Input $i sem informação de derivação — não sei qual chave usar pra assinar")
            require(deriv.masterFingerprint.toHex() == wallet.fingerprint) {
                "Esse PSBT foi montado por outra carteira (fingerprint ${deriv.masterFingerprint.toHex()} ≠ ${wallet.fingerprint} desta)."
            }
            require(deriv.path.size == 5) { "Caminho de derivação inesperado no input $i" }
            val chain = deriv.path[3]
            val index = deriv.path[4]

            val hdKey = KeyDerivation.bip86(seed, coin = network.coinType, account = 0, change = chain, address = index)
            val tweakedPrivKey = Secp256k1.taprootTweakPrivateKey(hdKey.privateKey)
            try {
                val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(hdKey.privateKey)
                require(input.tapInternalKey != null && xOnly.contentEquals(input.tapInternalKey)) {
                    "Internal key derivada não bate com a do PSBT no input $i — dado corrompido ou adulterado, assinatura recusada."
                }
                val expectedSpk = byteArrayOf(0x51, 0x20) + Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
                require(expectedSpk.contentEquals(utxo.scriptPubKey)) {
                    "scriptPubKey do input $i não bate com o esperado pra essa chave — assinatura recusada."
                }

                val sighash = TaprootSighashCalculator.calculate(tx = psbt.unsignedTx, inputIndex = i, utxos = allUtxos)
                psbt.inputs[i].tapKeySig = SchnorrSigner.sign(msg32 = sighash, privKey32 = tweakedPrivKey)
            } finally {
                tweakedPrivKey.fill(0)
                hdKey.privateKey.fill(0)
            }
        }

        val rawTxBytes = psbt.finalize()
        return rawTxBytes.joinToString("") { "%02x".format(it) } to psbt.unsignedTx.txid()
    }

    private fun humanizeError(e: Exception): String {
        val msg = e.message ?: "Erro desconhecido"
        return when {
            msg.contains("RIPEMD160", ignoreCase = true) ->
                "Erro ao inicializar criptografia. Reinicie o app e tente novamente."
            msg.contains("network", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("connect", ignoreCase = true) ->
                "Sem conexão com a rede. Verifique seu internet e tente novamente."
            msg.contains("xpub", ignoreCase = true) ->
                "Dados da wallet corrompidos. Tente restaurar a partir do mnemonic."
            else -> msg
        }
    }

    private fun addressToScriptPubKey(address: String, network: Network): ByteArray {
        val (hrp, data) = Bech32.decode(address) ?: error("Endereço inválido: $address")
        require(hrp == network.hrp) {
            "Endereço de destino é de outra rede (prefixo \"$hrp\", esperado \"${network.hrp}\") — confira se não colou um endereço testnet numa wallet mainnet (ou vice-versa)."
        }
        require(data.isNotEmpty())
        val witnessVersion = data[0].toInt()
        require(witnessVersion in 0..16) { "Versão de witness inválida no endereço: $witnessVersion" }
        val program5bit    = data.copyOfRange(1, data.size)
        val prog5Bytes     = ByteArray(program5bit.size) { program5bit[it].toByte() }
        val progInts       = Bech32.convertBits(prog5Bytes, 5, 8, false)
        val programBytes   = ByteArray(progInts.size) { progInts[it].toByte() }
        require(
            if (witnessVersion == 0) programBytes.size == 20 || programBytes.size == 32
            else programBytes.size in 2..40
        ) { "Tamanho de programa inválido pra witness v$witnessVersion no endereço: ${programBytes.size} bytes" }
        val versionOpcode  = if (witnessVersion == 0) 0x00.toByte() else (0x50 + witnessVersion).toByte()
        return byteArrayOf(versionOpcode, programBytes.size.toByte()) + programBytes
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
