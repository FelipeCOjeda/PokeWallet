package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Valida o tweak Taproot (BIP341, key-path, sem script tree) contra o
 * vetor de teste OFICIAL do BIP341 (bitcoin/bips, wallet-test-vectors.json,
 * keyPathSpending[0].inputSpending[0] — txinIndex 0, merkleRoot null).
 *
 * Isso substitui um teste real em testnet: se bater com o vetor oficial,
 * a matemática do tweak (usada tanto pra endereço quanto pra assinatura)
 * está correta.
 */
class TaprootTweakTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    @Test
    fun `tweaked private key matches official BIP341 vector`() {
        val internalPrivkey = hex("6b973d88838f27366ed61c9ad6367663045cb456e28335c109e30717ae0c6baa")
        val expectedTweakedPrivkey = "2405b971772ad26915c8dcdf10f238753a9b837e5f8e6a86fd7c0cce5b7296d9"

        val tweaked = Secp256k1.taprootTweakPrivateKey(internalPrivkey)

        assertEquals(expectedTweakedPrivkey, tweaked.toHex())
    }

    @Test
    fun `internal x-only pubkey matches official BIP341 vector`() {
        val internalPrivkey = hex("6b973d88838f27366ed61c9ad6367663045cb456e28335c109e30717ae0c6baa")
        val expectedInternalPubkey = "d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d"

        val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(internalPrivkey)

        assertEquals(expectedInternalPubkey, xOnly.toHex())
    }

    @Test
    fun `output key derived from internal pubkey matches the P2TR scriptPubKey in the vector`() {
        // internalPubkey do input 0 (txinIndex 0, merkleRoot null)
        val internalPubkey = hex("d6889cb081036e0faefa3a35157ad71086b123b2b144b649798b494c300a961d")
        // x-only da scriptPubKey do utxo 0 (utxosSpent[0], "5120<32 bytes>")
        val expectedOutputKey = "53a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343"

        val outputKey = Secp256k1.taprootOutputKeyFromInternalXOnly(internalPubkey)

        assertEquals(expectedOutputKey, outputKey.toHex())
    }

    @Test
    fun `output key from privkey-based tweak matches output key from pubkey-based tweak`() {
        // Garante que os dois caminhos (assinatura via privkey vs endereço
        // watch-only via xpub/pubkey) concordam no MESMO output key —
        // é exatamente essa consistência que faz o app conseguir gastar
        // depois o que recebeu.
        val internalPrivkey = hex("6b973d88838f27366ed61c9ad6367663045cb456e28335c109e30717ae0c6baa")
        val internalPubkey  = Secp256k1.xOnlyPublicKeyFromPrivate(internalPrivkey)

        val outputKeyFromPubkey = Secp256k1.taprootOutputKeyFromInternalXOnly(internalPubkey)

        val tweakedPrivkey = Secp256k1.taprootTweakPrivateKey(internalPrivkey)
        val outputKeyFromPrivkey = Secp256k1.xOnlyPublicKeyFromPrivate(tweakedPrivkey)

        assertEquals(outputKeyFromPubkey.toHex(), outputKeyFromPrivkey.toHex())
    }
}
