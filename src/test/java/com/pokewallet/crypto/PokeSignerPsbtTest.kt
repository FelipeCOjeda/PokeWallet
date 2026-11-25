package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.varInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * PokeSignerPsbt.finalizeSigned(): PSBT com assinatura parcial (como o
 * PokeSigner devolve por BLE) → tx final com o witness certo e o mesmo
 * txid da tx não-assinada.
 */
class PokeSignerPsbtTest {

    private fun tx(spk: ByteArray) = UnsignedTransaction(
        version = 2,
        inputs = listOf(TxIn(prevTxId = ByteArray(32) { 0x11 }, prevIndex = 0, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)),
        outputs = listOf(TxOut(value = 40_000L, scriptPubKey = ByteArray(22) { 0x44 })),
        lockTime = 0L
    )

    private fun kv(out: ByteArrayOutputStream, key: ByteArray, value: ByteArray) {
        out.write(varInt(key.size.toLong())); out.write(key)
        out.write(varInt(value.size.toLong())); out.write(value)
    }

    private fun signedPsbt(spk: ByteArray, sigKey: ByteArray, sig: ByteArray): Pair<ByteArray, UnsignedTransaction> {
        val utx = tx(spk)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
        kv(out, byteArrayOf(0x00), utx.serializeLegacy())
        out.write(0x00)
        kv(out, byteArrayOf(0x01), TxOut(value = 50_000L, scriptPubKey = spk).serialize())
        kv(out, sigKey, sig)
        out.write(0x00)
        out.write(0x00) // mapa da saída vazio
        return out.toByteArray() to utx
    }

    @Test
    fun finalizesP2wpkhPsbt() {
        val spk = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x22 }
        val pub = byteArrayOf(0x02) + ByteArray(32) { 0x33 }
        val sig = ByteArray(70) { 0x55 } + byteArrayOf(0x01)
        val (psbt, utx) = signedPsbt(spk, byteArrayOf(0x02) + pub, sig)

        val hex = PokeSignerPsbt.finalizeSigned(psbt)

        assertEquals(utx.txid(), parseSignedTxidHex(hex))
        // witness: 2 itens, <sig> <pubkey>
        assertTrue(hex.contains("02" + "47" + sig.toHex() + "21" + pub.toHex()))
    }

    @Test
    fun finalizesP2trPsbt() {
        val spk = byteArrayOf(0x51, 0x20) + ByteArray(32) { 0x22 }
        val sig = ByteArray(64) { 0x66 }
        val (psbt, utx) = signedPsbt(spk, byteArrayOf(0x13), sig)

        val hex = PokeSignerPsbt.finalizeSigned(psbt)

        assertEquals(utx.txid(), parseSignedTxidHex(hex))
        // witness: 1 item de 64 bytes
        assertTrue(hex.contains("01" + "40" + sig.toHex()))
    }
}
