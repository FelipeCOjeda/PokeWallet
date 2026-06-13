package com.pokewallet.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

object WalletEncryption {

    // 0xAE identifies encrypted wallet files; never a valid JSON first byte ('{' = 0x7B)
    const val MAGIC: Byte = 0xAE.toByte()

    private const val KEY_ALIAS = "pokewallet_aes256"
    private const val KEYSTORE  = "AndroidKeyStore"
    private const val IV_LEN    = 12   // 96-bit IV — optimal for GCM
    private const val TAG_BITS  = 128  // GCM authentication tag

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** Returns [MAGIC(1)] + [IV(12)] + [ciphertext + 16-byte GCM tag] */
    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv         = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return byteArrayOf(MAGIC) + iv + ciphertext
    }

    /** Decrypts data produced by encrypt(). Throws on tampering. */
    fun decrypt(data: ByteArray): String {
        require(data.size > 1 + IV_LEN) { "Dados criptografados inválidos." }
        val iv         = data.copyOfRange(1, 1 + IV_LEN)
        val ciphertext = data.copyOfRange(1 + IV_LEN, data.size)
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
