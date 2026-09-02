package com.pokewallet.crypto

import com.pokewallet.network.SilentPaymentsConfirmer
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

    /**
     * Descarta o cache de JSON decriptado em memória (mnemonic/passphrase
     * em texto puro, ver doc de [cachedRawJson]) — chamada quando o app vai
     * pro background (MainActivity.onStop()). Mitigação, não solução
     * completa: `String` do Java/Kotlin é imutável, não dá pra zerar os
     * bytes de verdade (só descartar a referência e deixar o GC coletar
     * quando quiser) — o cache ainda existe enquanto o app está em uso
     * ativo (é o motivo dele existir: evitar redecriptar AES-GCM em toda
     * chamada de load(), dezenas de vezes por sessão), mas não sobrevive
     * indefinidamente pelo tempo de vida inteiro do processo. Próximo
     * load() depois disso volta a decriptar do disco normalmente.
     */
    fun clearCache(): Unit = synchronized(lock) {
        cachedRawJson = null
    }

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

        if (!json.has("spUtxos")) {
            json.put("spUtxos", org.json.JSONArray())
            json.put("spScanTipHeight", 0L)
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

        val spUtxosArray = json.optJSONArray("spUtxos") ?: org.json.JSONArray()
        val spUtxos = (0 until spUtxosArray.length()).map { i ->
            val o = spUtxosArray.getJSONObject(i)
            SilentPaymentsConfirmer.ConfirmedUtxo(
                txid              = o.getString("txid"),
                vout              = o.getInt("vout"),
                valueSats         = o.getLong("valueSats"),
                outputXOnlyPubKey = o.getString("outputXOnlyPubKeyHex").hexToBytes(),
                tweak             = o.getString("tweakHex").hexToBytes(),
                k                 = o.getInt("k"),
                blockHeight       = o.getLong("blockHeight")
            )
        }

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
            // isNull() (não has()) porque save() grava JSONObject.NULL
            // explícito pra "ainda sem saldo conhecido" em vez de omitir a
            // chave — has() voltaria true e getLong() lançaria em cima do NULL.
            cachedBalanceSats  = if (!json.isNull("cachedBalanceSats")) json.getLong("cachedBalanceSats") else null,
            cachedPendingSats  = if (!json.isNull("cachedPendingSats")) json.getLong("cachedPendingSats") else null,
            cachedUtxoCount    = if (!json.isNull("cachedUtxoCount")) json.getInt("cachedUtxoCount") else null,
            cachedScanTimeMs   = if (!json.isNull("cachedScanTimeMs")) json.getLong("cachedScanTimeMs") else null,
            frozenUtxoKeys     = frozenKeys,
            spUtxos            = spUtxos,
            spScanTipHeight    = json.optLong("spScanTipHeight", 0L),
            birthHeight        = if (json.has("birthHeight") && !json.isNull("birthHeight")) json.getLong("birthHeight") else null,
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
        wallet.raw.put("cachedBalanceSats", wallet.cachedBalanceSats ?: JSONObject.NULL)
        wallet.raw.put("cachedPendingSats", wallet.cachedPendingSats ?: JSONObject.NULL)
        wallet.raw.put("cachedUtxoCount", wallet.cachedUtxoCount ?: JSONObject.NULL)
        wallet.raw.put("cachedScanTimeMs", wallet.cachedScanTimeMs ?: JSONObject.NULL)
        wallet.raw.put("spUtxos", org.json.JSONArray(wallet.spUtxos.map { u ->
            JSONObject()
                .put("txid", u.txid)
                .put("vout", u.vout)
                .put("valueSats", u.valueSats)
                .put("outputXOnlyPubKeyHex", u.outputXOnlyPubKey.toHex())
                .put("tweakHex", u.tweak.toHex())
                .put("k", u.k)
                .put("blockHeight", u.blockHeight)
        }))
        wallet.raw.put("spScanTipHeight", wallet.spScanTipHeight)
        wallet.raw.put("birthHeight", wallet.birthHeight ?: JSONObject.NULL)
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
        // Marca já como "ativo" no momento da reserva, não só quando o scan
        // encontra atividade nele. Sem isso: reservar de novo (ex.: abrir
        // Receber duas vezes) avança nextExternalIndex/nextInternalIndex
        // pra além deste índice, e como ele nunca tinha atividade conhecida,
        // o scan incremental (que só reverifica knownActive + a fronteira
        // atual pra frente) para de olhar pra ele pra sempre — um pagamento
        // que chegasse aqui depois ficaria invisível mesmo com o endereço
        // certo e o scan rodando sem erro.
        wallet.activeInternalIndices = wallet.activeInternalIndices + index
        saveLocked(wallet)
        index
    }

    /** Mesma lógica de reserveNextInternalIndex(), pro índice externo (recebimento). */
    fun reserveNextExternalIndex(): Int = synchronized(lock) {
        val wallet = loadLocked()
        val index = wallet.nextExternalIndex
        wallet.nextExternalIndex = index + 1
        wallet.activeExternalIndices = wallet.activeExternalIndices + index
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

    /**
     * Mescla UTXOs SP recém-CONFIRMADOS (ver SilentPaymentsConfirmer — nunca
     * chame isto com candidatos ainda não confirmados contra uma fonte
     * própria) na lista persistida, deduplicando por "txid:vout", e avança
     * spScanTipHeight — mesma seção crítica load→muta→save das outras
     * operações aqui, pra um scan e uma troca de carteira concorrentes
     * nunca se pisarem.
     */
    fun addSilentPaymentUtxos(newUtxos: List<SilentPaymentsConfirmer.ConfirmedUtxo>, newScanTipHeight: Long): Unit = synchronized(lock) {
        val wallet = loadLocked()
        val existingKeys = wallet.spUtxos.mapTo(mutableSetOf()) { "${it.txid}:${it.vout}" }
        val toAdd = newUtxos.filterNot { "${it.txid}:${it.vout}" in existingKeys }
        wallet.spUtxos = wallet.spUtxos + toAdd
        wallet.spScanTipHeight = maxOf(wallet.spScanTipHeight, newScanTipHeight)
        saveLocked(wallet)
    }

    fun delete(): Boolean = synchronized(lock) {
        cachedRawJson = null
        secureDelete(walletFile)
    }

    /**
     * Sobrescreve o conteúdo do arquivo com zeros antes de apagar — File.
     * delete() sozinho só desvincula o nome do arquivo, os bytes antigos
     * podem continuar recuperáveis por forense de armazenamento até serem
     * reescritos por outra coisa. NÃO é garantia absoluta em flash/eMMC
     * (wear-leveling pode gravar a sobrescrita num bloco físico diferente
     * do original) — mas é a mesma mitigação best-effort que qualquer
     * wallet consciente disso aplica, e helps mais o esquema aqui porque
     * wallet.json já é AES-256-GCM (WalletEncryption) — isso é defesa
     * adicional pro caso do Keystore em si ser comprometido depois.
     */
    fun secureDelete(file: File): Boolean {
        if (!file.exists()) return true
        overwriteWithZeros(file)
        return file.delete()
    }

    /** Separada de secureDelete() só pra ser testável isoladamente (sem
     *  o delete() logo em seguida escondendo se a sobrescrita rodou). */
    internal fun overwriteWithZeros(file: File) {
        try {
            val length = file.length()
            java.io.RandomAccessFile(file, "rws").use { raf ->
                raf.seek(0)
                raf.write(ByteArray(length.toInt()))
            }
        } catch (_: Exception) {
            // Sobrescrita é best-effort — se falhar (ex: permissão), ainda
            // tenta apagar o arquivo normalmente em secureDelete() em vez
            // de travar a operação de esquecer a wallet.
        }
    }
}
