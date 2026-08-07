package com.pokewallet.crypto

import org.json.JSONObject
import java.time.Instant

/**
 * Importa uma carteira watch-only (só xpub, sem seed), de duas formas:
 *
 * 1. String completa "accountOrigin" que a própria WalletInit/WalletRestore
 *    já exportam — "[fingerprintHex/purpose'/coin'/0']xpub", a mesma que
 *    fica embutida nos descriptors (Fase C2, tela "Ver Chave Pública").
 *    Traz o fingerprint REAL da chave mestra, então essa carteira consegue
 *    montar PSBT air-gapped que o lado assinante confere de cara.
 * 2. Xpub pura ("xpub6C...", "zpub...", etc, sem colchetes) — mais fácil de
 *    colar (é o que várias outras wallets tipo BlueWallet exportam), mas
 *    matematicamente não carrega o fingerprint da chave mestra (um xpub de
 *    conta só carrega o fingerprint do PAI imediato, dado diferente,
 *    impossível recuperar o mestre a partir dele). Nesse caso o
 *    fingerprint salvo é só um pseudo-ID interno (evita colisão de
 *    diretório entre múltiplas carteiras importadas assim) e
 *    hasVerifiedFingerprint=false — o PSBT air-gapped grava fingerprint
 *    "desconhecido" (zeros, convenção BIP174) em vez desse pseudo-ID; o
 *    lado assinante então pula a checagem de fingerprint e confia só na
 *    checagem de script (que é a garantia de segurança real: só assina se
 *    a chave derivada localmente bater com o UTXO declarado no PSBT).
 */
object WalletWatchOnlyImport {

    // "[" de abertura é OPCIONAL de propósito: é o primeiro caractere da
    // string inteira, fácil de perder ao copiar manualmente (seleção de
    // texto começando 1 caractere tarde, campo cortando a borda esquerda
    // etc.) — o "]" de fechamento continua obrigatório, já é suficiente
    // pra não confundir com a xpub pura (que não tem barra "/" nem "]").
    private val ORIGIN_REGEX = Regex("^\\[?([0-9a-f]{8})/(\\d+)[h']/(\\d+)[h']/0[h']\\]([a-zA-Z0-9]+)$")
    private val BARE_XPUB_REGEX = Regex("^(xpub|ypub|zpub|tpub|upub|vpub)[a-zA-Z0-9]+$")

    /** Resultado do parsing, ANTES de gravar nada — permite ao chamador (ex.:
     *  WalletViewModel) checar se o fingerprint já colide com uma carteira
     *  existente NO DISPOSITIVO antes de decidir se segue com o import. */
    data class ParsedOrigin(
        val fingerprintHex: String,
        val xpub: String,
        val origin: String,
        val hasVerifiedFingerprint: Boolean
    )

    fun parse(accountOrigin: String, network: Network, spendType: SpendType): ParsedOrigin {
        val trimmed = accountOrigin.trim()

        val bracketMatch = ORIGIN_REGEX.find(trimmed)
        val fingerprintHex: String
        val purposeStr: String
        val coinStr: String
        val xpub: String
        val origin: String
        val hasVerifiedFingerprint: Boolean

        if (bracketMatch != null) {
            val (fp, p, c, x) = bracketMatch.destructured
            fingerprintHex = fp
            purposeStr = p
            coinStr = c
            xpub = x
            // Reconstrói sempre a partir das partes (em vez de reusar `trimmed`
            // direto) — garante o "[" de abertura mesmo quando o usuário colou
            // sem ele, pra accountOrigin salvo ficar sempre bem-formado (é
            // reusado dentro dos descriptors wpkh(...)/tr(...) depois).
            origin = "[$fp/${p}h/${c}h/0h]$x"
            hasVerifiedFingerprint = true
        } else if (BARE_XPUB_REGEX.matches(trimmed)) {
            fingerprintHex = pseudoFingerprint(trimmed)
            purposeStr = spendType.bipPurpose().toString()
            coinStr = network.coinType.toString()
            xpub = trimmed
            origin = "[$fingerprintHex/${purposeStr}h/${coinStr}h/0h]$trimmed"
            hasVerifiedFingerprint = false
        } else {
            error("Formato inválido — cole a xpub (ex: xpub6C...) ou a chave pública completa \"[fingerprint/path]xpub\" exportada da tela \"Ver Chave Pública\".")
        }

        require(xpub.startsWith("xpub") || xpub.startsWith("ypub") || xpub.startsWith("zpub") ||
                xpub.startsWith("tpub") || xpub.startsWith("upub") || xpub.startsWith("vpub")) {
            "XPUB inválido dentro da chave pública informada."
        }

        val expectedPurpose = spendType.bipPurpose()
        require(purposeStr.toInt() == expectedPurpose) {
            "Essa chave é de purpose $purposeStr' — não bate com o tipo de endereço escolhido (esperado $expectedPurpose')."
        }
        require(coinStr.toInt() == network.coinType) {
            "Essa chave é de outra rede (coin type $coinStr') — não bate com a rede escolhida (esperado ${network.coinType}')."
        }

        return ParsedOrigin(fingerprintHex, xpub, origin, hasVerifiedFingerprint)
    }

    fun run(accountOrigin: String, network: Network, spendType: SpendType) {
        require(!WalletStorage.exists()) { "Wallet já existe. Use 'Esquecer Wallet' antes de importar." }

        val parsed = parse(accountOrigin, network, spendType)

        val walletName = "pokewallet_${parsed.fingerprintHex}"
        val externalDescriptor = when (spendType) {
            SpendType.BIP84 -> "wpkh(${parsed.origin}/0/*)"
            SpendType.BIP86 -> "tr(${parsed.origin}/0/*)"
        }
        val internalDescriptor = when (spendType) {
            SpendType.BIP84 -> "wpkh(${parsed.origin}/1/*)"
            SpendType.BIP86 -> "tr(${parsed.origin}/1/*)"
        }

        val json = JSONObject()
            .put("version",            2)
            .put("walletName",         walletName)
            .put("network",            network.name)
            .put("spendType",          spendType.name)
            .put("fingerprint",        parsed.fingerprintHex)
            .put("hasVerifiedFingerprint", parsed.hasVerifiedFingerprint)
            .put("xpub",               parsed.xpub)
            .put("accountOrigin",      parsed.origin)
            .put("externalDescriptor", externalDescriptor)
            .put("internalDescriptor", internalDescriptor)
            .put("nextExternalIndex",  0)
            .put("activeExternalIndices", org.json.JSONArray())
            .put("activeInternalIndices", org.json.JSONArray())
            .put("nextInternalIndex",  0)
            .put("createdAt",          Instant.now().toString())
            .put("mnemonicVerified",   true) // não há seed pra confirmar
            .put("isWatchOnly",        true)

        WalletStorage.saveRaw(json)
    }

    /** ID interno determinístico derivado da própria xpub — só pra dar um
     *  walletId/diretório único quando não há fingerprint mestre real (não
     *  deve nunca ser usado como fingerprint dentro de um PSBT). */
    private fun pseudoFingerprint(xpub: String): String =
        Hashes.sha256(xpub.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4).toHex()
}
