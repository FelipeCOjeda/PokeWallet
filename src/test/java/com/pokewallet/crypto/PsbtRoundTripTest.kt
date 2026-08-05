package com.pokewallet.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip encode→decode do PSBT (BIP174/BIP371) — a peça de crypto
 * nova mais sensível da Fase C (watch-only air-gapped), testada isolada
 * de UI/QR/dispositivo antes de qualquer integração, como recomendado na
 * revisão do plano. Cobre só o que este app realmente serializa: tx não-
 * assinada, witness_utxo e bip32_derivation (input/output) — nunca uma
 * assinatura dentro do PSBT (a volta do signer é uma tx bruta finalizada,
 * não um PSBT parcialmente assinado).
 */
class PsbtRoundTripTest {

    private fun fakeTxid(byte: Int) = ByteArray(32) { byte.toByte() }
    private fun fakeScript(byte: Int) = ByteArray(22) { byte.toByte() } // tamanho de um P2WPKH real

    private fun sampleUnsignedTx() = UnsignedTransaction(
        version = 2,
        inputs = listOf(
            TxIn(prevTxId = fakeTxid(0x11), prevIndex = 0, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)
        ),
        outputs = listOf(
            TxOut(value = 50_000L, scriptPubKey = fakeScript(0x22)),  // destino
            TxOut(value = 9_000L, scriptPubKey = fakeScript(0x33))    // troco
        ),
        lockTime = 0L
    )

    // ── Bip32Derivation / TapBip32Derivation (funções puras) ──────────

    @Test
    fun bip32DerivationRoundTrips() {
        val original = Bip32Derivation(
            masterFingerprint = byteArrayOf(0xa9.toByte(), 0xfe.toByte(), 0x84.toByte(), 0x8d.toByte()),
            path = listOf(KeyDerivation.hardened(84), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 0, 5)
        )
        val decoded = Bip32Derivation.parse(original.serialize())
        assertArrayEquals(original.masterFingerprint, decoded.masterFingerprint)
        assertEquals(original.path, decoded.path)
    }

    @Test
    fun tapBip32DerivationRoundTripsWithNoLeafHashes() {
        val original = TapBip32Derivation(
            leafHashes = emptyList(), // key-path only — este app nunca usa tapscript
            masterFingerprint = byteArrayOf(0xa9.toByte(), 0xfe.toByte(), 0x84.toByte(), 0x8d.toByte()),
            path = listOf(KeyDerivation.hardened(86), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 1, 2)
        )
        val decoded = TapBip32Derivation.parse(original.serialize())
        assertTrue(decoded.leafHashes.isEmpty())
        assertArrayEquals(original.masterFingerprint, decoded.masterFingerprint)
        assertEquals(original.path, decoded.path)
    }

    // ── UnsignedTransaction — base do mapa global do PSBT ──────────────

    @Test
    fun unsignedTransactionSerializeLegacyRoundTrips() {
        val original = sampleUnsignedTx()
        val decoded = UnsignedTransaction.parse(original.serializeLegacy())

        assertEquals(original.version, decoded.version)
        assertEquals(original.lockTime, decoded.lockTime)
        assertEquals(original.txid(), decoded.txid())
        assertEquals(original.inputs.size, decoded.inputs.size)
        assertArrayEquals(original.inputs[0].prevTxId, decoded.inputs[0].prevTxId)
        assertEquals(original.inputs[0].prevIndex, decoded.inputs[0].prevIndex)
        original.outputs.zip(decoded.outputs).forEach { (o, d) ->
            assertEquals(o.value, d.value)
            assertArrayEquals(o.scriptPubKey, d.scriptPubKey)
        }
    }

    // ── Psbt (SegWit v0 / BIP84) ────────────────────────────────────

    @Test
    fun psbtSegwitV0RoundTripPreservesWitnessUtxoAndBip32Derivation() {
        val unsignedTx = sampleUnsignedTx()
        val pubKeyHex = "02" + "ab".repeat(32) // 33 bytes comprimido (fake, mas tamanho real)
        val changeOutPubKeyHex = "03" + "cd".repeat(32)

        val psbt = Psbt(
            unsignedTx = unsignedTx,
            inputs = mutableListOf(PsbtInput().apply {
                witnessUtxo = TxOut(value = 100_000L, scriptPubKey = fakeScript(0x44))
                bip32Derivations[pubKeyHex] = Bip32Derivation(
                    masterFingerprint = byteArrayOf(1, 2, 3, 4),
                    path = listOf(KeyDerivation.hardened(84), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 0, 7)
                )
            }),
            outputs = mutableListOf(
                PsbtOutput(), // destino externo — sem derivation, não é chave desta carteira
                PsbtOutput().apply {
                    bip32Derivations[changeOutPubKeyHex] = Bip32Derivation(
                        masterFingerprint = byteArrayOf(1, 2, 3, 4),
                        path = listOf(KeyDerivation.hardened(84), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 1, 3)
                    )
                }
            )
        )

        val base64 = psbt.serializeBase64()
        val decoded = Psbt.parseBase64(base64)

        assertEquals(psbt.unsignedTx.txid(), decoded.unsignedTx.txid())
        assertEquals(1, decoded.inputs.size)
        assertEquals(2, decoded.outputs.size)

        val decodedWitnessUtxo = decoded.inputs[0].witnessUtxo!!
        assertEquals(100_000L, decodedWitnessUtxo.value)
        assertArrayEquals(fakeScript(0x44), decodedWitnessUtxo.scriptPubKey)

        val decodedInputDeriv = decoded.inputs[0].bip32Derivations[pubKeyHex]!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decodedInputDeriv.masterFingerprint)
        assertEquals(listOf(KeyDerivation.hardened(84), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 0, 7), decodedInputDeriv.path)

        // Output de destino: nenhuma derivation (não é chave desta carteira)
        assertTrue(decoded.outputs[0].bip32Derivations.isEmpty())

        // Output de troco: derivation presente e correta
        val decodedChangeDeriv = decoded.outputs[1].bip32Derivations[changeOutPubKeyHex]!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decodedChangeDeriv.masterFingerprint)
        assertEquals(listOf(KeyDerivation.hardened(84), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 1, 3), decodedChangeDeriv.path)

        // Nenhuma assinatura vaza no round-trip — PSBT sempre não-assinado
        assertTrue(decoded.inputs[0].partialSignatures.isEmpty())
        assertNull(decoded.inputs[0].finalWitness)
    }

    // ── PsbtTaproot (BIP86) ─────────────────────────────────────────

    @Test
    fun psbtTaprootRoundTripPreservesInternalKeyAndTapDerivation() {
        val unsignedTx = sampleUnsignedTx()
        val xOnlyPubKeyHex = "ab".repeat(32) // 32 bytes x-only (fake, tamanho real)
        val changeXOnlyPubKeyHex = "cd".repeat(32)

        val psbt = PsbtTaproot(
            unsignedTx = unsignedTx,
            inputs = mutableListOf(TaprootPsbtInput().apply {
                witnessUtxo = TxOut(value = 100_000L, scriptPubKey = fakeScript(0x55))
                tapInternalKey = xOnlyPubKeyHex.hexToBytes()
                tapBip32Derivation = TapBip32Derivation(
                    leafHashes = emptyList(),
                    masterFingerprint = byteArrayOf(9, 8, 7, 6),
                    path = listOf(KeyDerivation.hardened(86), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 0, 11)
                )
            }),
            outputs = mutableListOf(
                PsbtOutput(),
                PsbtOutput().apply {
                    tapBip32Derivations[changeXOnlyPubKeyHex] = TapBip32Derivation(
                        leafHashes = emptyList(),
                        masterFingerprint = byteArrayOf(9, 8, 7, 6),
                        path = listOf(KeyDerivation.hardened(86), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 1, 4)
                    )
                }
            )
        )

        val base64 = psbt.serializeBase64()
        val decoded = PsbtTaproot.parseBase64(base64)

        assertEquals(psbt.unsignedTx.txid(), decoded.unsignedTx.txid())
        assertEquals(1, decoded.inputs.size)
        assertEquals(2, decoded.outputs.size)

        val decodedWitnessUtxo = decoded.inputs[0].witnessUtxo!!
        assertEquals(100_000L, decodedWitnessUtxo.value)
        assertArrayEquals(fakeScript(0x55), decodedWitnessUtxo.scriptPubKey)

        assertArrayEquals(xOnlyPubKeyHex.hexToBytes(), decoded.inputs[0].tapInternalKey)

        val decodedDeriv = decoded.inputs[0].tapBip32Derivation!!
        assertTrue(decodedDeriv.leafHashes.isEmpty())
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), decodedDeriv.masterFingerprint)
        assertEquals(listOf(KeyDerivation.hardened(86), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 0, 11), decodedDeriv.path)

        assertTrue(decoded.outputs[0].tapBip32Derivations.isEmpty())

        val decodedChangeDeriv = decoded.outputs[1].tapBip32Derivations[changeXOnlyPubKeyHex]!!
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), decodedChangeDeriv.masterFingerprint)
        assertEquals(listOf(KeyDerivation.hardened(86), KeyDerivation.hardened(0), KeyDerivation.hardened(0), 1, 4), decodedChangeDeriv.path)

        assertNull(decoded.inputs[0].tapKeySig)
        assertNull(decoded.inputs[0].finalWitness)
    }

    // ── Rejeição de PSBT inválido ───────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun parseBase64RejectsWrongMagicBytes() {
        val garbage = java.util.Base64.getEncoder().encodeToString(ByteArray(20) { 0x00 })
        Psbt.parseBase64(garbage)
    }
}
