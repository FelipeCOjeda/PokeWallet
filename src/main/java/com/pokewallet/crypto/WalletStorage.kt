package com.pokewallet.crypto

import org.json.JSONObject
import java.io.File

object WalletStorage {

    var filesDir: File = File(".")
        set(value) {
            field = value
            cachedRawJson = null // muda o diretório -> cache do wallet.json antigo não vale mais
        }

    private val walletFile get() = File(filesDir, "wallet.json")

    /**
     * Serializa TODO load()/save() — sem isso, dois load-modifica-save
     * concorrentes (ex.: um envio reservando o índice de troco ao mesmo
     * tempo que um refresh de saldo em background) podem se sobrescrever
     * um ao outro. reserveNextInternalIndex()/reserveNextExternalIndex()
     * usam o mesmo lock pra tornar "ler o índice atual + incrementar +
     * persistir" uma operação atômica, fechando o caso mais concreto (dois
     * envios/recebimentos concorrentes reutilizando o mesmo índice).
     */
    private val lock = Any()

    /**
     * Cache do JSON já decriptado — evita reler+redecriptar (AES-GCM) o
     * arquivo do disco em toda chamada de load(), que acontece dezenas de
     * vezes por ciclo de vida normal do app. Invalidado (setado pro
     * conteúdo novo) em toda escrita, e limpo em delete()/troca de
     * filesDir. Cada load() ainda faz um JSONObject(...) novo a partir do
     * cache — nunca devolve a MESMA instância de WalletData/JSONObject pra
     * chamadores diferentes (eles mutam nextExternalIndex/nextInternalIndex
     * e o próprio JSONObject diretamente antes de chamar save(), então
     * compartilhar a instância entre chamadores seria inseguro).
     */
    private var cachedRawJson: String? = null

    fun exists(): Boolean = walletFile.exists()

    fun load(): WalletData = synchronized(lock) { loadLocked() }

    private fun loadLocked(): WalletData {

        val rawJson: String = cachedRawJson ?: run {
            require(walletFile.exists()) {
                "wallet.json não encontrado. Rode wallet-init primeiro."
            }

            val bytes = walletFile.readBytes()

            // Detect encrypted (magic byte 0xAE) vs legacy plaintext (starts with '{')
            val decrypted: String = if (bytes.isNotEmpty() && bytes[0] == WalletEncryption.MAGIC) {
                WalletEncryption.decrypt(bytes)
            } else {
                // Legacy plaintext — migrate to encrypted on the spot
                val text = String(bytes, Charsets.UTF_8)
                walletFile.writeBytes(WalletEncryption.encrypt(text))
                text
            }

            decrypted.also { cachedRawJson = it }
        }

        val json = JSONObject(rawJson)

        WalletSchemaValidator.validate(json)

        var dirty = false

        if (!json.has("walletName")) {
            json.put("walletName", "pokewallet")
            dirty = true
        }

        if (!json.has("nextExternalIndex")) {
            json.put("nextExternalIndex", 0)
            dirty = true
        }

        if (!json.has("nextInternalIndex")) {
            json.put("nextInternalIndex", 0)
            dirty = true
        }

        if (!json.has("mnemonicVerified")) {
            // wallet.json de antes desse campo existir: trata como não-verificada —
            // é o lado seguro (pior caso, pede pra confirmar de novo; não trava o acesso).
            json.put("mnemonicVerified", false)
            dirty = true
        }

        if (dirty) {
            val migrated = json.toString(2)
            walletFile.writeBytes(WalletEncryption.encrypt(migrated))
            cachedRawJson = migrated
        }

        val mnemonic = json
            .getString("mnemonic")
            .trim()
            .split(Regex("\\s+"))

        val fingerprintHex = json.getString("fingerprint")

        return WalletData(
            walletName         = json.getString("walletName"),
            mnemonic           = mnemonic,
            passphrase         = json.getString("passphrase"),
            mnemonicVerified   = json.getBoolean("mnemonicVerified"),
            fingerprint        = fingerprintHex,
            network            = Network.valueOf(json.getString("network")),
            spendType          = SpendType.valueOf(json.getString("spendType")),
            xpub               = json.optString("xpub", null),
            nextExternalIndex  = json.getInt("nextExternalIndex"),
            nextInternalIndex  = json.getInt("nextInternalIndex"),
            raw                = json
        )
    }

    fun save(wallet: WalletData): Unit = synchronized(lock) { saveLocked(wallet) }

    private fun saveLocked(wallet: WalletData) {
        wallet.raw.put("nextExternalIndex", wallet.nextExternalIndex)
        wallet.raw.put("nextInternalIndex", wallet.nextInternalIndex)
        val serialized = wallet.raw.toString(2)
        walletFile.writeBytes(WalletEncryption.encrypt(serialized))
        cachedRawJson = serialized
    }

    /** Write a freshly-built JSONObject as encrypted wallet.json (used by WalletInit/WalletRestore). */
    fun saveRaw(json: JSONObject): Unit = synchronized(lock) {
        val serialized = json.toString(2)
        walletFile.writeBytes(WalletEncryption.encrypt(serialized))
        cachedRawJson = serialized
    }

    /**
     * Lê o próximo índice de troco (interno) e já incrementa + persiste
     * antes de devolver — numa única seção crítica, pra duas chamadas
     * concorrentes (dois envios que geram troco ao mesmo tempo) nunca
     * reservarem o mesmo índice.
     */
    fun reserveNextInternalIndex(): Int = synchronized(lock) {
        val wallet = loadLocked()
        val index = wallet.nextInternalIndex
        wallet.nextInternalIndex = index + 1
        saveLocked(wallet)
        index
    }

    /** Mesma lógica de reserveNextInternalIndex(), pro índice externo (recebimento). */
    fun reserveNextExternalIndex(): Int = synchronized(lock) {
        val wallet = loadLocked()
        val index = wallet.nextExternalIndex
        wallet.nextExternalIndex = index + 1
        saveLocked(wallet)
        index
    }

    fun delete(): Boolean = synchronized(lock) {
        cachedRawJson = null
        walletFile.delete()
    }
}
