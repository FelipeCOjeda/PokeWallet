package com.pokewallet.crypto

import org.json.JSONObject

/**
 * Representa o estado persistido da wallet.
 *
 * Este objeto é a fonte de verdade em memória,
 * refletindo exatamente o conteúdo do wallet.json.
 */
data class WalletData(

    // -----------------------------
    // Identidade da wallet no Core
    // -----------------------------
    val walletName: String,

    // -----------------------------
    // Segredo / Seed
    // -----------------------------
    val mnemonic: List<String>,
    val passphrase: String,
    val mnemonicVerified: Boolean,

    // -----------------------------
    // Identidade BIP32
    // -----------------------------
    val fingerprint: String,

    // -----------------------------
    // Configuração
    // -----------------------------
    val network: Network,
    val spendType: SpendType,

    // -----------------------------
    // Chaves públicas
    // -----------------------------
    val xpub: String?,

    // -----------------------------
    // Índices HD (estado mutável)
    // -----------------------------
    var nextExternalIndex: Int,
    var nextInternalIndex: Int,

    // -----------------------------
    // JSON bruto (preservação futura)
    // -----------------------------
    val raw: JSONObject
)
