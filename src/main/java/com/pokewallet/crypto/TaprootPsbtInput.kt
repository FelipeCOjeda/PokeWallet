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
}

