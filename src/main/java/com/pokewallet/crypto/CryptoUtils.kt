package com.pokewallet.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    private val secureRandom = SecureRandom()

    /** 256 bits de entropia (32 bytes) */
    fun randomEntropy256(): ByteArray {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /** SHA-256 */
    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** SHA256(SHA256(data)) — usado em entropy final */
    fun doubleSha256(data: ByteArray): ByteArray =
        sha256(sha256(data))

    /** HMAC-SHA512 (BIP32 / BIP39) */
    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /**
     * PBKDF2-HMAC-SHA512
     *
     * BIP39:
     * seed = PBKDF2(
     *   password = mnemonic,
     *   salt = "mnemonic" + passphrase,
     *   iterations = 2048,
     *   keyLength = 512 bits
     * )
     */
    fun pbkdf2Sha512(
        mnemonic: String,
        salt: String,
        iterations: Int = 2048,
        keyLengthBits: Int = 512
    ): ByteArray {
        val spec = PBEKeySpec(
            mnemonic.toCharArray(),
            salt.toByteArray(),
            iterations,
            keyLengthBits
        )

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }
}
