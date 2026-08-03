package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vetores de teste OFICIAIS do BIP84 (bip-0084.mediawiki) e BIP86
 * (bip-0086.mediawiki), baixados direto do GitHub (bitcoin/bips) — usam
 * o mnemonic padrão "abandon...about" (12x abandon + about), sem
 * passphrase. Testam a pipeline inteira: seed → KeyDerivation.bip84/86 →
 * Secp256k1 → AddressBuilder, contra endereços bc1q.../bc1p... conhecidos.
 */
class AddressBuilderTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(" ")
    private val seed = Bip39.mnemonicToSeed(mnemonic, passphrase = "")

    // ---------- BIP84 (Native SegWit) ----------

    @Test
    fun bip84FirstReceivingAddress() {
        val key = KeyDerivation.bip84(seed, coin = 0, account = 0, change = 0, address = 0)
        val pubKeyHash = Hashes.hash160(Secp256k1.publicKeyFromPrivate(key.privateKey))
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            AddressBuilder.p2wpkh(pubKeyHash, Network.MAINNET)
        )
    }

    @Test
    fun bip84SecondReceivingAddress() {
        val key = KeyDerivation.bip84(seed, coin = 0, account = 0, change = 0, address = 1)
        val pubKeyHash = Hashes.hash160(Secp256k1.publicKeyFromPrivate(key.privateKey))
        assertEquals(
            "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g",
            AddressBuilder.p2wpkh(pubKeyHash, Network.MAINNET)
        )
    }

    @Test
    fun bip84FirstChangeAddress() {
        val key = KeyDerivation.bip84(seed, coin = 0, account = 0, change = 1, address = 0)
        val pubKeyHash = Hashes.hash160(Secp256k1.publicKeyFromPrivate(key.privateKey))
        assertEquals(
            "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el",
            AddressBuilder.p2wpkh(pubKeyHash, Network.MAINNET)
        )
    }

    // ---------- BIP86 (Taproot) ----------

    @Test
    fun bip86FirstReceivingAddress() {
        val key = KeyDerivation.bip86(seed, coin = 0, account = 0, change = 0, address = 0)
        val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(key.privateKey)
        assertEquals(
            "cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115",
            xOnly.toHex()
        )
        val outputKey = Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
        assertEquals(
            "a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c",
            outputKey.toHex()
        )
        assertEquals(
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
            AddressBuilder.p2tr(outputKey, Network.MAINNET)
        )
    }

    @Test
    fun bip86FirstChangeAddress() {
        val key = KeyDerivation.bip86(seed, coin = 0, account = 0, change = 1, address = 0)
        val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(key.privateKey)
        val outputKey = Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
        assertEquals(
            "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7",
            AddressBuilder.p2tr(outputKey, Network.MAINNET)
        )
    }
}
