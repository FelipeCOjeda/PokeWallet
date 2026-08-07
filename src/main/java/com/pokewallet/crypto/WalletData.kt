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
    /** false quando [fingerprint] é um pseudo-ID interno (import watch-only
     *  por xpub pura, sem o fingerprint real da chave mestra) — nesse caso
     *  o PSBT air-gapped grava fingerprint desconhecido (zeros) em vez
     *  deste valor, pra não colidir com a checagem do lado assinante. */
    val hasVerifiedFingerprint: Boolean,

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

    /** Índices (chain 0/1) que JÁ mostraram atividade em algum scan
     *  anterior — usado pelo scan incremental (WalletScanner.scan) pra
     *  saber quais endereços precisam ser RE-verificados (podem ter sido
     *  gastos ou recebido mais) sem precisar re-varrer o intervalo inteiro
     *  desde o índice 0 toda vez. Só cresce (uma vez ativo, sempre conta
     *  como ativo — tx_count nunca diminui). */
    var activeExternalIndices: Set<Int>,
    var activeInternalIndices: Set<Int>,
    /** true só logo após migrar um wallet.json de antes do scan incremental
     *  existir (nextIndex > 0 mas activeIndices desconhecido) — força UMA
     *  varredura completa no próximo scan antes de confiar no modo
     *  incremental. Ver migração em WalletStorage.loadLocked(). */
    var needsFullRescan: Boolean = false,

    // -----------------------------
    // UTXOs congelados ("txid:vout"), fora da seleção automática e manual
    // -----------------------------
    val frozenUtxoKeys: Set<String>,

    // -----------------------------
    // JSON bruto (preservação futura)
    // -----------------------------
    val raw: JSONObject
)
