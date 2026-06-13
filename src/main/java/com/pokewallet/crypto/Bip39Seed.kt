package com.pokewallet.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Bip39Seed {

    fun fromMnemonic(
        mnemonic: List<String>,
        passphrase: String = ""
    ): ByteArray {

        val sentence = mnemonic.joinToString(" ")
        val salt = "mnemonic$passphrase"

        val spec = PBEKeySpec(
            sentence.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            2048,
            512
        )

        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return skf.generateSecret(spec).encoded
    }
}
