package com.pokewallet.crypto

/**
 * Schnorr Signer — BIP340
 *
 * Wrapper fino sobre Secp256k1.
 *
 * Responsabilidade:
 *  - validar entradas
 *  - manter semântica clara no código
 *
 * NÃO implementa matemática de curva.
 * Isso pertence exclusivamente a Secp256k1.kt.
 */
object SchnorrSigner {

    /**
     * Assina uma mensagem de 32 bytes usando Schnorr (BIP340)
     *
     * @param msg32 mensagem (32 bytes)
     * @param privKey32 chave privada (32 bytes)
     * @param auxRand32 opcional (IGNORADO — BIP340 padrão usa zeros)
     *
     * @return assinatura Schnorr (64 bytes)
     */
    fun sign(
        msg32: ByteArray,
        privKey32: ByteArray,
        auxRand32: ByteArray? = null
    ): ByteArray {

        require(msg32.size == 32) {
            "msg32 deve ter 32 bytes"
        }

        require(privKey32.size == 32) {
            "privKey32 deve ter 32 bytes"
        }

        // auxRand32 é aceito por compatibilidade futura,
        // mas o Secp256k1.signSchnorr já segue o BIP340 correto
        return Secp256k1.signSchnorr(
            privateKey = privKey32,
            messageHash = msg32
        )
    }
}
