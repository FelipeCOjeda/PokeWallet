package com.pokewallet.crypto

/**
 * Taproot PSBT Input — mínimo funcional (BIP86)
 */
class TaprootPsbtInput {

    /** witness UTXO (obrigatório em Taproot) */
    var witnessUtxo: TxOut? = null

    /** assinatura Schnorr (64 bytes [+ sighash opcional]) */
    var tapKeySig: ByteArray? = null

    /** witness final (key-path = 1 item) */
    var finalWitness: List<ByteArray>? = null

    /** PSBT_IN_TAP_INTERNAL_KEY (0x17, BIP371) — pubkey x-only (32 bytes) antes do tweak. */
    var tapInternalKey: ByteArray? = null

    /** PSBT_IN_TAP_BIP32_DERIVATION (0x16, BIP371) — só key-path, leafHashes sempre vazio. */
    var tapBip32Derivation: TapBip32Derivation? = null
}

