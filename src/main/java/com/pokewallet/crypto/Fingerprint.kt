package com.pokewallet.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BIP32 Fingerprint
 *
 * fingerprint = first 32 bits of HASH160(compressed public key)
 */
object Fingerprint {

    fun of(key: HDKey): Int {
        // Compressed public key (33 bytes)
        val pub = Secp256k1.publicKeyFromPrivate(key.privateKey)

        // HASH160 = RIPEMD160(SHA256(pub))
        val hash160 = Hashes.hash160(pub)

        // First 4 bytes, big-endian (BIP32 spec)
        return ByteBuffer
            .wrap(hash160, 0, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
    }
}
