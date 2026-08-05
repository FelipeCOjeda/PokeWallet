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
    // Segredo / Seed — null quando isWatchOnly (importada só por xpub, sem
    // acesso à chave privada nesta carteira/dispositivo)
    // -----------------------------
    val mnemonic: List<String>?,
    val passphrase: String?,
    val mnemonicVerified: Boolean,
    val isWatchOnly: Boolean,

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
    /** "[fingerprintHex/purpose'/coin'/0']xpub" — mesma string já usada nos
     *  descriptors, exportável via QR pra parear uma carteira watch-only
     *  noutro dispositivo sem digitação manual. */
    val accountOrigin: String?,

    // -----------------------------
    // Índices HD (estado mutável)
    // -----------------------------
    var nextExternalIndex: Int,
    var nextInternalIndex: Int,

    // -----------------------------
    // UTXOs congelados ("txid:vout"), fora da seleção automática e manual
    // -----------------------------
    val frozenUtxoKeys: Set<String>,

    // -----------------------------
    // JSON bruto (preservação futura)
    // -----------------------------
    val raw: JSONObject
)
