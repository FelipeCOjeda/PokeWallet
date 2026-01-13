package com.pokewallet.crypto

import java.security.MessageDigest
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

object Hashes {

    init {
        // Garante que o BouncyCastle esteja registrado
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** SHA-256 */
    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** RIPEMD-160 (via BouncyCastle) */
    fun ripemd160(data: ByteArray): ByteArray =
        MessageDigest.getInstance("RIPEMD160", "BC").digest(data)

    /**
     * Bitcoin HASH160
     * RIPEMD160(SHA256(data))
     */
    fun hash160(data: ByteArray): ByteArray =
        ripemd160(sha256(data))
}
