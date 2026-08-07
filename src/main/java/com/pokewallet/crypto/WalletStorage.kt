package com.pokewallet.crypto

import org.json.JSONObject
import java.io.File

object WalletStorage {

    var filesDir: File = File(".")
        set(value) = synchronized(lock) {
            // Sincronizado no mesmo lock de load()/save() — troca de carteira
            // (reatribuir filesDir) nunca pode cair no meio de uma sequência
            // carregar→mutar→salvar de outra chamada em andamento.
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

        if (!json.has("activeExternalIndices")) {
            // Carteira já existia ANTES desse campo — nextExternalIndex/
            // nextInternalIndex podem já ser > 0 (uso real, endereços com
            // saldo) mas não há como saber QUAIS índices historicamente
            // tiveram atividade. needsFullRescan=true força UMA varredura
            // completa (índice 0 até o gap limit, igual sempre foi) no
            // PRÓXIMO scan pra reconstruir essa lista com segurança — sem
            // isso, o scan incremental só olharia a partir de
            // nextExternalIndex em diante e endereços antigos com saldo
            // ficariam invisíveis pra sempre. Só essa PRIMEIRA varredura
            // paga o custo cheio; dali em diante volta a ser incremental.
            json.put("activeExternalIndices", org.json.JSONArray())
            json.put("activeInternalIndices", org.json.JSONArray())
            json.put("needsFullRescan", true)
            dirty = true
        }

        if (!json.has("mnemonicVerified")) {
            // wallet.json de antes desse campo existir: trata como não-verificada —
            // é o lado seguro (pior caso, pede pra confirmar de novo; não trava o acesso).
            json.put("mnemonicVerified", false)
            dirty = true
        }

        if (!json.has("frozenUtxos")) {
            json.put("frozenUtxos", org.json.JSONArray())
            dirty = true
        }

        if (!json.has("isWatchOnly")) {
            json.put("isWatchOnly", false)
            dirty = true
        }

        if (!json.has("hasVerifiedFingerprint")) {
            // wallet.json de antes desse campo existir: seeded ou watch-only
            // pelo formato completo "[fp/path]xpub" sempre tiveram o
            // fingerprint real da chave mestra — só o import por xpub pura
            // (novo) grava false explicitamente na criação.
            json.put("hasVerifiedFingerprint", true)
            dirty = true
        }

        if (!json.has("accountOrigin")) {
            // wallet.json de antes desse campo existir: sintetiza uma vez a
            // partir do externalDescriptor já salvo (mesma string "[fp/path]xpub"
            // que fica dentro de wpkh(...)/tr(...)) — carteira watch-only nunca
            // cai aqui, ela já grava accountOrigin direto na criação.
            val desc = json.optString("externalDescriptor", "")
            Regex("\\[[^\\]]+\\][a-zA-Z0-9]+").find(desc)?.let { match ->
                json.put("accountOrigin", match.value)
                dirty = true
            }
        }

        if (dirty) {
            val migrated = json.toString(2)
            walletFile.writeBytes(WalletEncryption.encrypt(migrated))
            cachedRawJson = migrated
        }

        val isWatchOnly = json.optBoolean("isWatchOnly", false)

        val mnemonic = if (isWatchOnly) null else json
            .getString("mnemonic")
            .trim()
            .split(Regex("\\s+"))
        val passphrase = if (isWatchOnly) null else json.getString("passphrase")

        val fingerprintHex = json.getString("fingerprint")

        val frozenArray = json.optJSONArray("frozenUtxos") ?: org.json.JSONArray()
        val frozenKeys = (0 until frozenArray.length()).map { frozenArray.getString(it) }.toSet()

        fun intSet(field: String): Set<Int> {
            val arr = json.optJSONArray(field) ?: return emptySet()
            return (0 until arr.length()).mapTo(mutableSetOf()) { arr.getInt(it) }
        }

        return WalletData(
            walletName         = json.getString("walletName"),
            mnemonic           = mnemonic,
            passphrase         = passphrase,
            mnemonicVerified   = json.getBoolean("mnemonicVerified"),
            isWatchOnly        = isWatchOnly,
            fingerprint        = fingerprintHex,
            hasVerifiedFingerprint = json.optBoolean("hasVerifiedFingerprint", true),
            network            = Network.valueOf(json.getString("network")),
            spendType          = SpendType.valueOf(json.getString("spendType")),
            xpub               = json.optString("xpub", null),
            accountOrigin      = json.optString("accountOrigin", null),
            nextExternalIndex  = json.getInt("nextExternalIndex"),
            nextInternalIndex  = json.getInt("nextInternalIndex"),
            activeExternalIndices = intSet("activeExternalIndices"),
            activeInternalIndices = intSet("activeInternalIndices"),
            needsFullRescan    = json.optBoolean("needsFullRescan", false),
            frozenUtxoKeys     = frozenKeys,
            raw                = json
        )
    }

    fun save(wallet: WalletData): Unit = synchronized(lock) { saveLocked(wallet) }

    private fun saveLocked(wallet: WalletData) {
        wallet.raw.put("nextExternalIndex", wallet.nextExternalIndex)
        wallet.raw.put("nextInternalIndex", wallet.nextInternalIndex)
        wallet.raw.put("activeExternalIndices", org.json.JSONArray(wallet.activeExternalIndices))
        wallet.raw.put("activeInternalIndices", org.json.JSONArray(wallet.activeInternalIndices))
        wallet.raw.put("needsFullRescan", wallet.needsFullRescan)
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
     * Lê só o campo isWatchOnly do wallet.json de um diretório específico —
     * SEM tocar em [filesDir]/cache (que representam a carteira ATIVA).
     * Usado pra listar o tipo de cada carteira conhecida (ex.: pokébola
     * cinza/colorida na Mochila) sem trocar qual está ativa nem interferir
     * numa troca de carteira em andamento noutra thread.
     */
    fun peekIsWatchOnly(dir: File): Boolean {
        val file = File(dir, "wallet.json")
        if (!file.exists()) return false
        val bytes = file.readBytes()
        val decrypted = if (bytes.isNotEmpty() && bytes[0] == WalletEncryption.MAGIC) {
            WalletEncryption.decrypt(bytes)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return JSONObject(decrypted).optBoolean("isWatchOnly", false)
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

    /**
     * Congela/descongela um UTXO ("txid:vout") — mesma seção crítica
     * load→muta→save de reserveNext*Index(), pra duas chamadas concorrentes
     * (ou uma troca de carteira no meio) nunca perderem uma da outra.
     */
    fun setUtxoFrozen(key: String, frozen: Boolean): Unit = synchronized(lock) {
        val wallet = loadLocked()
        val current = wallet.raw.optJSONArray("frozenUtxos") ?: org.json.JSONArray()
        val keys = (0 until current.length()).mapTo(LinkedHashSet()) { current.getString(it) }
        if (frozen) keys.add(key) else keys.remove(key)
        wallet.raw.put("frozenUtxos", org.json.JSONArray(keys))
        saveLocked(wallet)
    }

    fun delete(): Boolean = synchronized(lock) {
        cachedRawJson = null
        walletFile.delete()
    }
}
