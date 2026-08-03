package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vetores de teste OFICIAIS do BIP39, do conjunto canônico usado por
 * praticamente toda implementação (trezor/python-mnemonic, vectors.json,
 * lista "english") — baixados direto do GitHub, não digitados de memória.
 * Seed derivada com passphrase "TREZOR" (mesma usada pelos vetores).
 */
class Bip39Test {

    @Test
    fun generatesMnemonicFrom16ByteEntropy_allZero() {
        val entropy = "00000000000000000000000000000000".hexToBytes()
        val mnemonic = Bip39.generateMnemonic(entropy)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            mnemonic.joinToString(" ")
        )
    }

    @Test
    fun generatesMnemonicFrom16ByteEntropy_allFf() {
        val entropy = "ffffffffffffffffffffffffffffffff".hexToBytes()
        val mnemonic = Bip39.generateMnemonic(entropy)
        assertEquals(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            mnemonic.joinToString(" ")
        )
    }

    @Test
    fun generatesMnemonicFrom32ByteEntropy_allZero() {
        val entropy = "0000000000000000000000000000000000000000000000000000000000000000".hexToBytes()
        assertEquals(32, entropy.size)
        val mnemonic = Bip39.generateMnemonic(entropy)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
            mnemonic.joinToString(" ")
        )
    }

    @Test
    fun derivesSeedFromMnemonic_allZero16Byte() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(" ")
        val seed = Bip39.mnemonicToSeed(mnemonic, passphrase = "TREZOR")
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            seed.toHex()
        )
    }

    @Test
    fun derivesSeedFromMnemonic_allFf16Byte() {
        val mnemonic = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong".split(" ")
        val seed = Bip39.mnemonicToSeed(mnemonic, passphrase = "TREZOR")
        assertEquals(
            "ac27495480225222079d7be181583751e86f571027b0497b5b5d11218e0a8a13332572917f0f8e5a589620c6f15b11c61dee327651a14c34e18231052e48c069",
            seed.toHex()
        )
    }

    @Test
    fun derivesSeedFromMnemonic_allZero32Byte() {
        val mnemonic =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
                .split(" ")
        val seed = Bip39.mnemonicToSeed(mnemonic, passphrase = "TREZOR")
        assertEquals(
            "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097170af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8",
            seed.toHex()
        )
    }
}
