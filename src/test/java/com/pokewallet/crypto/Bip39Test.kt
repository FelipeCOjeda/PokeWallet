package com.pokewallet.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.text.Normalizer

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

    // ── Correção do achado ALTO da auditoria (item 10): normalização NFKD ──

    @Test
    fun `mnemonicToSeed normaliza passphrase NFKD - formas Unicode diferentes da mesma passphrase visivel produzem a mesma seed`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

        // "café" — NFC: "é" como 1 codepoint precomposto (U+00E9).
        // NFD: "e" + acento agudo combinante em separado (U+0065 U+0301).
        // Visualmente idênticas, bytes diferentes — exatamente o cenário
        // de um usuário digitando a mesma passphrase em teclados/IMEs
        // diferentes sem normalizar do lado da wallet.
        val nfcForm = Normalizer.normalize("café", Normalizer.Form.NFC)
        val nfdForm = Normalizer.normalize("café", Normalizer.Form.NFD)
        assertNotEquals("sanity check: as duas formas têm que ser strings Java diferentes de fato", nfcForm, nfdForm)

        val seedFromNfc = Bip39.mnemonicToSeed(mnemonic, nfcForm)
        val seedFromNfd = Bip39.mnemonicToSeed(mnemonic, nfdForm)

        assertArrayEquals(
            "com normalização NFKD, as duas formas da mesma passphrase visível devem gerar a MESMA seed",
            seedFromNfc, seedFromNfd
        )
    }

    @Test
    fun `mnemonicToSeed com passphrase so ASCII nao muda com a normalizacao - vetor oficial continua batendo`() {
        // "TREZOR" já é NFKD-estável (ASCII puro) — os vetores oficiais
        // acima continuam batendo exatamente como antes da normalização;
        // este teste só deixa esse invariante explícito.
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val normalized = Normalizer.normalize("TREZOR", Normalizer.Form.NFKD)
        assertEquals("TREZOR", normalized)
        val seed = Bip39.mnemonicToSeed(mnemonic, passphrase = "TREZOR")
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            seed.toHex()
        )
    }
}
