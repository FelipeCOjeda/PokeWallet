package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * toDisplayPrefix() reescreve só os 4 bytes de versão (xpub→zpub) — testa
 * que o payload (depth/fingerprint/index/chaincode/pubkey) fica IDÊNTICO,
 * só o prefixo textual muda, e que BIP86 não é afetado (sem SLIP-132
 * padronizado pra Taproot).
 */
class ExtendedKeySerializerDisplayPrefixTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun `BIP84 mainnet xpub vira zpub mantendo o mesmo payload`() {
        val seed = SeedDerivation.fromMnemonic(testMnemonic, "")
        val accountKey = KeyDerivation.derive(
            seed, intArrayOf(KeyDerivation.hardened(84), KeyDerivation.hardened(0), KeyDerivation.hardened(0))
        )
        val xpub = XpubEncoder.encode(accountKey, Network.MAINNET)
        assertTrue(xpub.startsWith("xpub"))

        val zpub = ExtendedKeySerializer.toDisplayPrefix(xpub, SpendType.BIP84, Network.MAINNET)
        assertTrue("esperava prefixo zpub, veio: $zpub", zpub.startsWith("zpub"))

        val xpubPayload = com.pokewallet.Base58.decodeCheck(xpub)
        val zpubPayload = com.pokewallet.Base58.decodeCheck(zpub)
        assertEquals(
            "payload (depth/fp/index/chaincode/pubkey) deveria ser idêntico, só a versão muda",
            xpubPayload.copyOfRange(4, xpubPayload.size).toList(),
            zpubPayload.copyOfRange(4, zpubPayload.size).toList()
        )
    }

    @Test
    fun `BIP84 testnet xpub vira vpub`() {
        val seed = SeedDerivation.fromMnemonic(testMnemonic, "")
        val accountKey = KeyDerivation.derive(
            seed, intArrayOf(KeyDerivation.hardened(84), KeyDerivation.hardened(1), KeyDerivation.hardened(0))
        )
        val xpub = XpubEncoder.encode(accountKey, Network.TESTNET)
        val vpub = ExtendedKeySerializer.toDisplayPrefix(xpub, SpendType.BIP84, Network.TESTNET)
        assertTrue("esperava prefixo vpub, veio: $vpub", vpub.startsWith("vpub"))
    }

    @Test
    fun `BIP86 (Taproot) nao muda o prefixo -- sem SLIP-132 padronizado`() {
        val seed = SeedDerivation.fromMnemonic(testMnemonic, "")
        val accountKey = KeyDerivation.derive(
            seed, intArrayOf(KeyDerivation.hardened(86), KeyDerivation.hardened(0), KeyDerivation.hardened(0))
        )
        val xpub = XpubEncoder.encode(accountKey, Network.MAINNET)
        val result = ExtendedKeySerializer.toDisplayPrefix(xpub, SpendType.BIP86, Network.MAINNET)
        assertEquals(xpub, result)
    }
}
