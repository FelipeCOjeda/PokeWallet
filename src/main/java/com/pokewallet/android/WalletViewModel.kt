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
import kotlinx.coroutines.withContext
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
        val walletName: String,
        val network: Network,
        val balanceSats: Long?,
        val pendingSats: Long?,
        val utxoCount: Int?,
        val isScanning: Boolean,
        val scanStatus: String?,
        val lastScanTime: Date?
    ) : WalletState()
    data class Error(val message: String) : WalletState()
}

sealed class RestoreState {
    object Idle      : RestoreState()
    object Restoring : RestoreState()
    object Success   : RestoreState()
    data class Error(val message: String) : RestoreState()
}

data class WalletTx(
    val txid: String,
    val netSats: Long,
    val confirmed: Boolean,
    val blockTime: Long?
)

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

    private var lastKnownPendingSats: Long = 0L
    private var autoScanJob: Job? = null

    init {
        WalletStorage.filesDir = app.filesDir
        checkWallet()
    }

    private fun checkWallet() {
        if (!WalletStorage.exists()) {
            _walletState.value = WalletState.NoWallet
            return
        }

        viewModelScope.launch {
            try {
                val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                if (wallet.mnemonicVerified) {
                    loadWalletAndStartScan()
                } else {
                    // Wallet foi criada mas o usuário nunca completou o quiz de
                    // confirmação (app fechou/matou o processo antes) — retoma o
                    // fluxo de verificação em vez de liberar acesso à wallet.
                    val mode = PassphraseMode.fromPersisted(
                        wallet.raw.optString("passphraseMode"),
                        wallet.passphrase
                    )
                    _walletState.value = WalletState.Created(
                        mnemonic       = wallet.mnemonic.joinToString(" "),
                        passphrase     = wallet.passphrase,
                        passphraseMode = mode
                    )
                }
            } catch (e: Exception) {
                _walletState.value = WalletState.Error(humanizeError(e))
            }
        }
    }

    private fun loadWalletAndStartScan() {
        viewModelScope.launch {
            try {
                val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                _walletState.value = WalletState.Loaded(
                    walletName   = wallet.walletName,
                    network      = wallet.network,
                    balanceSats  = null,
                    pendingSats  = null,
                    utxoCount    = null,
                    isScanning   = false,
                    scanStatus   = null,
                    lastScanTime = null
                )
                startAutoScan()
            } catch (e: Exception) {
                _walletState.value = WalletState.Error(humanizeError(e))
            }
        }
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

    private suspend fun doScan() {
        val current = _walletState.value as? WalletState.Loaded ?: return

        _walletState.value = current.copy(isScanning = true, scanStatus = "Varrendo endereços…")

        try {
            val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
            val xpub = wallet.xpub ?: return
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
        spendType: SpendType = SpendType.BIP84
    ) {
        _walletState.value = WalletState.Creating
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { WalletInit.run(network, passphraseMode, wordCount, spendType) }
                val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                _walletState.value = WalletState.Created(
                    mnemonic       = wallet.mnemonic.joinToString(" "),
                    passphrase     = wallet.passphrase,
                    passphraseMode = passphraseMode
                )
            } catch (e: Exception) {
                _walletState.value = WalletState.Error(humanizeError(e))
            }
        }
    }

    fun onMnemonicConfirmed() {
        viewModelScope.launch {
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
            loadWalletAndStartScan()
        }
    }

    fun getReceiveAddress(): Pair<String, Int>? {
        return try {
            val wallet  = WalletStorage.load()
            val seed    = SeedDerivation.fromMnemonic(wallet.mnemonic, wallet.passphrase)
            // Reserva atômica — evita que dois toques em "Receber" concorrentes
            // (ou um toque colidindo com o índice de troco de um envio) derivem
            // o mesmo índice/endereço.
            val index   = WalletStorage.reserveNextExternalIndex()
            val address = ReceiveAddressService.addressAt(
                seed      = seed,
                spendType = wallet.spendType,
                network   = wallet.network,
                index     = index
            )
            Pair(address, index)
        } catch (_: Exception) {
            null
        }
    }

    fun sendFunds(
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        mode: SendMode = SendMode.Internet,
        feeRateSatPerVbyte: Double
    ) {
        _sendState.value = SendState.Sending
        viewModelScope.launch {
            try {
                require(feeRateSatPerVbyte >= 0.5) { "Taxa mínima é 0.5 sat/vB" }
                when (mode) {
                    is SendMode.Internet -> {
                        val txid = withContext(Dispatchers.IO) {
                            executeSend(destination, amountSats, sweep, feeRateSatPerVbyte)
                        }
                        _sendState.value = SendState.Success(txid, confirmedByRelay = true)
                    }
                    is SendMode.BitChat -> {
                        val result = withContext(Dispatchers.IO) {
                            executeSendViaNostr(destination, amountSats, sweep, feeRateSatPerVbyte)
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

    fun restoreWallet(words: List<String>, passphrase: String, network: Network, spendType: SpendType = SpendType.BIP84) {
        _restoreState.value = RestoreState.Restoring
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.pokewallet.crypto.WalletRestore.run(words, passphrase, network, spendType)
                }
                _restoreState.value = RestoreState.Success
                loadWalletAndStartScan()
            } catch (e: Exception) {
                _restoreState.value = RestoreState.Error(humanizeError(e))
            }
        }
    }

    fun resetRestoreState() { _restoreState.value = RestoreState.Idle }

    fun forgetWallet() {
        autoScanJob?.cancel()
        WalletStorage.delete()
        _walletState.value = WalletState.NoWallet
    }

    /** Transação assinada, pronta pra transmitir por qualquer um dos dois caminhos. */
    private data class PreparedTx(
        val rawTxHex: String,
        val txid: String,
        val seed: ByteArray,
        val network: Network
    )

    private fun executeSend(destination: String, amountSats: Long?, sweep: Boolean, feeRateSatPerVbyte: Double): String {
        val prepared = buildSignedTx(destination, amountSats, sweep, feeRateSatPerVbyte)
        return BlockstreamClient.broadcast(prepared.rawTxHex, prepared.network)
    }

    private suspend fun executeSendViaNostr(
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double
    ): NostrSendResult {
        val prepared = buildSignedTx(destination, amountSats, sweep, feeRateSatPerVbyte)

        _sendState.value = SendState.PublishingToRelays

        val (nostrPrivKey, nostrPubKey) = NostrKeys.deriveFromSeed(prepared.seed)
        val relays = GeoRelayDirectory.closestRelays(BITCHAT_GEOHASH)
        val event = NostrEvent.build(
            privKey32 = nostrPrivKey,
            pubKey32  = nostrPubKey,
            kind      = 20000,
            tags      = listOf(listOf("g", BITCHAT_GEOHASH)),
            content   = "!broadcast ${prepared.rawTxHex}"
        )

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

    private fun buildSignedTx(
        destination: String,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double
    ): PreparedTx {
        require(feeRateSatPerVbyte >= 0.5) { "Taxa mínima é 0.5 sat/vB" }

        val wallet  = WalletStorage.load()
        val xpub    = requireNotNull(wallet.xpub) { "xpub não encontrado" }
        val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
        val seed    = SeedDerivation.fromMnemonic(wallet.mnemonic, wallet.passphrase)

        val spendType = wallet.spendType

        val scanResult = WalletScanner.scan(xpub = xpub, network = network, spendType = spendType)
        if (scanResult.totalSats == 0L) error("Saldo zero — nada para enviar.")

        val feeRate = feeRateSatPerVbyte

        data class SpendableUtxo(
            val txidLE: ByteArray, val vout: Int, val valueSats: Long,
            val scriptPubKey: ByteArray, val privateKey: ByteArray, val pubKey: ByteArray
        )

        val spendable = mutableListOf<SpendableUtxo>()
        for (addr in scanResult.addressesWithFunds) {
            val hdKey = when (spendType) {
                SpendType.BIP84 -> KeyDerivation.bip84(seed, coin = network.coinType, account = 0,
                    change = addr.chain, address = addr.index)
                SpendType.BIP86 -> KeyDerivation.bip86(seed, coin = network.coinType, account = 0,
                    change = addr.chain, address = addr.index)
            }
            val privKey = hdKey.privateKey
            val pubKey  = Secp256k1.publicKeyFromPrivate(privKey)
            val spk = when (spendType) {
                SpendType.BIP84 -> byteArrayOf(0x00, 0x14) + Hashes.hash160(pubKey)
                SpendType.BIP86 -> {
                    val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(privKey)
                    byteArrayOf(0x51, 0x20) + Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
                }
            }
            for (utxo in addr.utxos) {
                spendable += SpendableUtxo(
                    txidLE       = hexToBytes(utxo.txid).reversedArray(),
                    vout         = utxo.vout,
                    valueSats    = utxo.valueSats,
                    scriptPubKey = spk,
                    privateKey   = privKey,
                    pubKey       = pubKey
                )
            }
        }

        val totalInputSats = spendable.sumOf { it.valueSats }
        val destSpk        = addressToScriptPubKey(destination, network)

        val plan = ChangePlanner.plan(
            totalInputSats     = totalInputSats,
            requestedAmount    = amountSats,
            sweep              = sweep,
            inputCount         = spendable.size,
            feeRateSatPerVbyte = feeRate
        )
        val sendAmount = plan.sendAmount

        val txOutputs: List<TxOut> = if (plan.changeValue != null) {
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

            listOf(TxOut(sendAmount, destSpk), TxOut(plan.changeValue, changeSpk))
        } else {
            listOf(TxOut(sendAmount, destSpk))
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
                    psbt.inputs[i].partialSignatures[s.pubKey] = sig
                }

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
                    psbt.inputs[i].tapKeySig = SchnorrSigner.sign(
                        msg32     = sighash,
                        privKey32 = tweakedPrivKey
                    )
                }

                Pair(psbt.finalize(), unsignedTx.txid())
            }
        }

        val rawTxHex = rawTxBytes.joinToString("") { "%02x".format(it) }

        return PreparedTx(rawTxHex = rawTxHex, txid = txid, seed = seed, network = network)
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
