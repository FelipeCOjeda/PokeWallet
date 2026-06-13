package com.pokewallet.crypto

import org.json.JSONObject

object WalletSchemaValidator {

    private const val CURRENT_VERSION = 2

    fun validate(json: JSONObject) {

        // -------------------------------------------------
        // Versão
        // -------------------------------------------------
        require(json.has("version")) {
            "wallet.json inválido: campo 'version' ausente"
        }

        val version = json.getInt("version")
        require(version == CURRENT_VERSION) {
            "wallet.json versão incompatível: $version (esperado $CURRENT_VERSION)"
        }

        // -------------------------------------------------
        // Identidade
        // -------------------------------------------------
        require(json.has("walletName")) {
            "wallet.json inválido: campo 'walletName' ausente"
        }

        val walletName = json.getString("walletName")
        require(walletName.isNotBlank()) {
            "walletName não pode ser vazio"
        }

        // -------------------------------------------------
        // Seed / segredo
        // -------------------------------------------------
        require(json.has("mnemonic")) {
            "wallet.json inválido: campo 'mnemonic' ausente"
        }

        val mnemonic = json.getString("mnemonic").trim()
        val words = mnemonic.split(Regex("\\s+"))

        require(words.size == 12 || words.size == 24) {
            "Mnemonic inválido: esperado 12 ou 24 palavras, encontrado ${words.size}"
        }

        require(json.has("passphrase")) {
            "wallet.json inválido: campo 'passphrase' ausente"
        }

        // -------------------------------------------------
        // Configuração
        // -------------------------------------------------
        require(json.has("network")) {
            "wallet.json inválido: campo 'network' ausente"
        }

        Network.valueOf(json.getString("network")) // lança exceção se inválido

        require(json.has("spendType")) {
            "wallet.json inválido: campo 'spendType' ausente"
        }

        SpendType.valueOf(json.getString("spendType")) // valida enum

        // -------------------------------------------------
        // Identidade BIP32 (CANÔNICA)
        // -------------------------------------------------
        require(json.has("fingerprint")) {
            "wallet.json inválido: campo 'fingerprint' ausente"
        }

        val fingerprint = json.getString("fingerprint")

        require(
            fingerprint.matches(Regex("^[0-9a-f]{8}$"))
        ) {
            "Fingerprint inválido: deve ser HEX BIP32 (8 chars, ex: a9fe848d)"
        }

        // -------------------------------------------------
        // XPUB
        // -------------------------------------------------
        require(json.has("xpub")) {
            "wallet.json inválido: campo 'xpub' ausente"
        }

        val xpub = json.getString("xpub")
        require(
            xpub.startsWith("xpub") ||
            xpub.startsWith("tpub") ||
            xpub.startsWith("zpub") ||
            xpub.startsWith("vpub")
        ) {
            "XPUB inválido ou incompatível com network"
        }

        // -------------------------------------------------
        // Estado HD
        // -------------------------------------------------
        require(json.has("nextExternalIndex")) {
            "wallet.json inválido: campo 'nextExternalIndex' ausente"
        }

        require(json.has("nextInternalIndex")) {
            "wallet.json inválido: campo 'nextInternalIndex' ausente"
        }

        val ext = json.getInt("nextExternalIndex")
        val int = json.getInt("nextInternalIndex")

        require(ext >= 0) { "nextExternalIndex não pode ser negativo" }
        require(int >= 0) { "nextInternalIndex não pode ser negativo" }

        // -------------------------------------------------
        // Metadata
        // -------------------------------------------------
        require(json.has("createdAt")) {
            "wallet.json inválido: campo 'createdAt' ausente"
        }
    }
}
