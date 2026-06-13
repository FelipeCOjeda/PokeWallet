package com.pokewallet.crypto

import java.security.MessageDigest
import java.security.SecureRandom

object Bip39Mnemonic {

    fun generateMnemonic(words: Int = 12): List<String> {
        require(words == 12 || words == 24)

        val entropyBits = if (words == 12) 128 else 256
        val entropyBytes = entropyBits / 8

        val entropy = ByteArray(entropyBytes)
        SecureRandom().nextBytes(entropy)

        val checksumBits = entropyBits / 32
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)

        val bits = buildString {
            entropy.forEach { append(it.toUByte().toString(2).padStart(8, '0')) }
            append(
                hash[0].toUByte().toString(2).padStart(8, '0')
                    .substring(0, checksumBits)
            )
        }

        return bits
            .chunked(11)
            .map { it.toInt(2) }
            .map { Bip39Wordlist.ENGLISH[it] }
    }
}
