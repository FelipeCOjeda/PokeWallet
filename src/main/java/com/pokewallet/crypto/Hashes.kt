package com.pokewallet.crypto

import java.security.MessageDigest
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

object Hashes {

    init {
        ensureBouncyCastle()
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

/**
 * Remove o BouncyCastle limitado do Android e insere o completo (com RIPEMD160).
 * Deve ser chamado antes de qualquer operação criptográfica.
 */
fun ensureBouncyCastle() {
    val existing = Security.getProvider("BC")
    if (existing == null || existing !is BouncyCastleProvider) {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
