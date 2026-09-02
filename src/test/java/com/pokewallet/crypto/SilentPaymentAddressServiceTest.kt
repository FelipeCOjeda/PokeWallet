package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 2 de Silent Payments (BIP-352): endereço próprio da carteira. Cada
 * peça (derivação de chave, encode bech32m) já foi validada isoladamente
 * em Bip352KeyDerivationTest/Bip352Test — este teste cobre só a "cola":
 * mesma seed → mesmo endereço sempre (determinístico), decodificar volta
 * pras mesmas chaves, e seeds diferentes dão endereços diferentes.
 */
class SilentPaymentAddressServiceTest {

    private fun seedOf(byte: Int) = ByteArray(64) { byte.toByte() }

    @Test
    fun `ownAddress e deterministico e decodifica de volta pras mesmas chaves`() {
        val seed = seedOf(0x11)
        val address = SilentPaymentAddressService.ownAddress(seed, Network.TESTNET)

        assertTrue(SilentPaymentAddress.looksLikeSilentPaymentAddress(address))
        assertTrue(address.startsWith("tsp1"))

        // Determinístico: mesma seed gera o MESMO endereço de novo.
        assertEquals(address, SilentPaymentAddressService.ownAddress(seed, Network.TESTNET))

        val scanPubKey  = Secp256k1.publicKeyFromPrivate(Bip352KeyDerivation.scanKey(seed, Network.TESTNET).privateKey)
        val spendPubKey = Secp256k1.publicKeyFromPrivate(Bip352KeyDerivation.spendKey(seed, Network.TESTNET).privateKey)

        val decoded = SilentPaymentAddress.decode(address, Network.TESTNET)
        assertEquals(scanPubKey.toHex(), decoded.scanPubKey.toHex())
        assertEquals(spendPubKey.toHex(), decoded.spendPubKey.toHex())
    }

    @Test
    fun `seeds diferentes geram enderecos diferentes`() {
        val addr1 = SilentPaymentAddressService.ownAddress(seedOf(0x11), Network.TESTNET)
        val addr2 = SilentPaymentAddressService.ownAddress(seedOf(0x22), Network.TESTNET)
        assertNotEquals(addr1, addr2)
    }

    @Test
    fun `mainnet usa HRP sp, testnet usa tsp`() {
        val seed = seedOf(0x33)
        assertTrue(SilentPaymentAddressService.ownAddress(seed, Network.MAINNET).startsWith("sp1"))
        assertTrue(SilentPaymentAddressService.ownAddress(seed, Network.TESTNET).startsWith("tsp1"))
    }
}
