package com.pokewallet.crypto

/**
 * AddressBuilder
 *
 * Responsável exclusivamente por:
 * - Validar insumos
 * - Escolher witness version
 * - Delegar encoding ao Bech32
 *
 * NÃO faz:
 * - derivação de chave
 * - coin selection
 * - lógica de rede
 */
object AddressBuilder {

    /**
     * BIP84 — P2WPKH (SegWit v0)
     * bc1 / tb1
     */
    fun p2wpkh(
        pubKeyHash: ByteArray,
        network: Network
    ): String {

        require(pubKeyHash.size == 20) {
            "PubKeyHash deve ter exatamente 20 bytes (HASH160)"
        }

        return Bech32.encodeSegWit(
            hrp = network.hrp,
            witnessVersion = 0,
            program = pubKeyHash
        )
    }

    /**
     * BIP86 — P2TR (Taproot key-path)
     * bc1p / tb1p
     */
    fun p2tr(
        xOnlyPubKey: ByteArray,
        network: Network
    ): String {

        require(xOnlyPubKey.size == 32) {
            "X-only pubkey deve ter exatamente 32 bytes"
        }

        return Bech32.encodeSegWit(
            hrp = network.hrp,
            witnessVersion = 1,
            program = xOnlyPubKey
        )
    }
}
