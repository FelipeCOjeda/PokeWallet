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
    data class Created(val mnemonic: String, val passphrase: String) : WalletState()
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
        if (WalletStorage.exists()) {
            loadWalletAndStartScan()
        } else {
            _walletState.value = WalletState.NoWallet
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
        autoScanJob = viewModelScope.launch {
            doScan()
            while (true) {
                delay(60_000L)
                doScan()
                loadPrice()
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

    fun createWallet(network: Network = Network.MAINNET) {
        _walletState.value = WalletState.Creating
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { WalletInit.run(network) }
                val wallet = withContext(Dispatchers.IO) { WalletStorage.load() }
                _walletState.value = WalletState.Created(
                    mnemonic   = wallet.mnemonic.joinToString(" "),
                    passphrase = wallet.passphrase
                )
            } catch (e: Exception) {
                _walletState.value = WalletState.Error(humanizeError(e))
            }
        }
    }

    fun onMnemonicConfirmed() {
        loadWalletAndStartScan()
    }

    fun getReceiveAddress(): Pair<String, Int>? {
        return try {
            val wallet  = WalletStorage.load()
            val seed    = SeedDerivation.fromMnemonic(wallet.mnemonic, wallet.passphrase)
            val index   = wallet.nextExternalIndex
            val address = ReceiveAddressService.addressAt(
                seed      = seed,
                spendType = wallet.spendType,
                network   = wallet.network,
                index     = index
            )
            wallet.nextExternalIndex = index + 1
            WalletStorage.save(wallet)
            Pair(address, index)
        } catch (_: Exception) {
            null
        }
    }

    fun sendFunds(destination: String, amountSats: Long?, sweep: Boolean, mode: SendMode = SendMode.Internet) {
        _sendState.value = SendState.Sending
        viewModelScope.launch {
            try {
                when (mode) {
                    is SendMode.Internet -> {
                        val txid = withContext(Dispatchers.IO) { executeSend(destination, amountSats, sweep) }
                        _sendState.value = SendState.Success(txid, confirmedByRelay = true)
                    }
                    is SendMode.BitChat -> {
                        val result = withContext(Dispatchers.IO) { executeSendViaNostr(destination, amountSats, sweep) }
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

    fun restoreWallet(words: List<String>, passphrase: String, network: Network) {
        _restoreState.value = RestoreState.Restoring
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.pokewallet.crypto.WalletRestore.run(words, passphrase, network)
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

    private fun executeSend(destination: String, amountSats: Long?, sweep: Boolean): String {
        val prepared = buildSignedTx(destination, amountSats, sweep)
        return BlockstreamClient.broadcast(prepared.rawTxHex, prepared.network)
    }

    private suspend fun executeSendViaNostr(destination: String, amountSats: Long?, sweep: Boolean): NostrSendResult {
        val prepared = buildSignedTx(destination, amountSats, sweep)

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

    private fun buildSignedTx(destination: String, amountSats: Long?, sweep: Boolean): PreparedTx {
        val wallet  = WalletStorage.load()
        val xpub    = requireNotNull(wallet.xpub) { "xpub não encontrado" }
        val network = if (wallet.network == Network.REGTEST) Network.TESTNET else wallet.network
        val seed    = SeedDerivation.fromMnemonic(wallet.mnemonic, wallet.passphrase)

        val scanResult = WalletScanner.scan(xpub = xpub, network = network)
        if (scanResult.totalSats == 0L) error("Saldo zero — nada para enviar.")

        val fees    = BlockstreamClient.getFeeEstimates(network)
        val feeRate = fees.halfHour.toLong().coerceAtLeast(1L)

        data class SpendableUtxo(
            val txidLE: ByteArray, val vout: Int, val valueSats: Long,
            val scriptPubKey: ByteArray, val privateKey: ByteArray, val pubKey: ByteArray
        )

        val spendable = mutableListOf<SpendableUtxo>()
        for (addr in scanResult.addressesWithFunds) {
            val hdKey   = KeyDerivation.bip84(seed, coin = network.coinType, account = 0,
                change = addr.chain, address = addr.index)
            val privKey = hdKey.privateKey
            val pubKey  = Secp256k1.publicKeyFromPrivate(privKey)
            val pkh     = Hashes.hash160(pubKey)
            val spk     = byteArrayOf(0x00, 0x14) + pkh
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
        val destSpk        = addressToScriptPubKey(destination)
        val fee            = FeeEstimator.estimateFee(spendable.size, 1, feeRate)
        val sendAmount     = if (sweep) totalInputSats - fee
                            else amountSats ?: error("Valor não informado")

        require(sendAmount > 546) { "Valor ($sendAmount sat) abaixo do dust limit após taxa de $fee sat" }
        require(sendAmount <= totalInputSats - fee) {
            "Saldo insuficiente: $totalInputSats sat disponíveis, fee $fee sat"
        }

        val txInputs   = spendable.map { s ->
            TxIn(prevTxId = s.txidLE, prevIndex = s.vout, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)
        }
        val txOutputs  = listOf(TxOut(sendAmount, destSpk))
        val unsignedTx = UnsignedTransaction(version = 2, inputs = txInputs, outputs = txOutputs, lockTime = 0L)

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

        val rawTxBytes = psbt.finalize()
        val rawTxHex   = rawTxBytes.joinToString("") { "%02x".format(it) }
        val txid       = psbt.txid()

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

    private fun addressToScriptPubKey(address: String): ByteArray {
        val (_, data) = Bech32.decode(address) ?: error("Endereço inválido: $address")
        require(data.isNotEmpty())
        val witnessVersion = data[0]
        val program5bit    = data.copyOfRange(1, data.size)
        val prog5Bytes     = ByteArray(program5bit.size) { program5bit[it].toByte() }
        val progInts       = Bech32.convertBits(prog5Bytes, 5, 8, false)
        val programBytes   = ByteArray(progInts.size) { progInts[it].toByte() }
        val versionOpcode  = if (witnessVersion == 0) 0x00.toByte() else (0x50 + witnessVersion).toByte()
        return byteArrayOf(versionOpcode, programBytes.size.toByte()) + programBytes
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
