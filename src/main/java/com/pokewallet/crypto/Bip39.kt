package com.pokewallet.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Bip39 {

    /**
     * Gera mnemonic BIP-39 a partir de entropy.
     * Aceita:
     * - 128 bits (16 bytes) → 12 palavras
     * - 256 bits (32 bytes) → 24 palavras
     */
    fun generateMnemonic(entropy: ByteArray): List<String> {

        require(entropy.size == 16 || entropy.size == 32) {
            "Entropy must be 128 or 256 bits"
        }

        val checksumBits = entropy.size * 8 / 32
        val entropyBits = entropy.toBitString()

        val checksum = CryptoUtils
            .sha256(entropy)
            .toBitString()
            .take(checksumBits)

        val bits = entropyBits + checksum

        return bits
            .chunked(11)
            .map { chunk ->
                val index = chunk.toInt(2)
                Bip39Wordlist.ENGLISH[index]
            }
    }

    /**
     * Converte mnemonic + passphrase em seed (BIP-39).
     * Passphrase vazia = wallet "default"
     * Passphrase diferente = outra wallet (negação plausível)
     */
    fun mnemonicToSeed(
        mnemonic: List<String>,
        passphrase: String = ""
    ): ByteArray {

        val sentence = mnemonic.joinToString(" ")
        val salt = "mnemonic$passphrase"

        val spec = PBEKeySpec(
            sentence.toCharArray(),
            salt.toByteArray(),
            2048,
            512
        )

        return SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(spec)
            .encoded
    }

    // -----------------------
    // helpers internos
    // -----------------------

    private fun ByteArray.toBitString(): String =
        joinToString("") { byte ->
            byte.toInt()
                .and(0xFF)
                .toString(2)
                .padStart(8, '0')
        }
}
