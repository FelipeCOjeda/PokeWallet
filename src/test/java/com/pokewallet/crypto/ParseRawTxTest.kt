package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.int32LE
import com.pokewallet.crypto.ByteSerializer.int64LE
import com.pokewallet.crypto.ByteSerializer.varInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [parseRawTx] é a base da defesa de [com.pokewallet.network.UtxoValueVerifier]
 * contra um provedor de dados mentindo o valor de um UTXO antes da wallet
 * assinar um gasto — cobre os dois formatos de serialização que uma
 * transação anterior pode ter (legado e SegWit com marker/flag/witness,
 * BIP144) e o caso em que os bytes recebidos não correspondem ao txid
 * esperado (o cenário que a verificação precisa detectar).
 */
class ParseRawTxTest {

    private fun fakeTxid(byte: Int) = ByteArray(32) { byte.toByte() }
    private fun fakeScript(byte: Int) = ByteArray(22) { byte.toByte() }

    private fun sampleTx() = UnsignedTransaction(
        version = 2,
        inputs = listOf(
            TxIn(prevTxId = fakeTxid(0x11), prevIndex = 0, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)
        ),
        outputs = listOf(
            TxOut(value = 123_456L, scriptPubKey = fakeScript(0x22)),
            TxOut(value = 7_000L, scriptPubKey = fakeScript(0x33))
        ),
        lockTime = 0L
    )

    /** Serialização SegWit completa (marker+flag+witness), formato BIP144
     *  real de uma tx transmitida — diferente de serializeLegacy(). */
    private fun serializeWithWitness(tx: UnsignedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(int32LE(tx.version))
        out.write(byteArrayOf(0x00, 0x01)) // marker, flag
        out.write(varInt(tx.inputs.size.toLong()))
        tx.inputs.forEach { input ->
            out.write(input.prevTxId)
            out.write(int32LE(input.prevIndex))
            out.write(varInt(0)) // scriptSig vazio
            out.write(int32LE(input.sequence.toInt()))
        }
        out.write(varInt(tx.outputs.size.toLong()))
        tx.outputs.forEach { output ->
            out.write(int64LE(output.value))
            out.write(varInt(output.scriptPubKey.size.toLong()))
            out.write(output.scriptPubKey)
        }
        // um item de witness fake por input (ex: uma assinatura P2WPKH)
        tx.inputs.forEach { _ ->
            out.write(varInt(1))
            val fakeSig = ByteArray(71) { 0xAB.toByte() }
            out.write(varInt(fakeSig.size.toLong()))
            out.write(fakeSig)
        }
        out.write(int32LE(tx.lockTime.toInt()))
        return out.toByteArray()
    }

    @Test
    fun `parseRawTx formato legado recalcula o txid certo e extrai as saidas`() {
        val tx = sampleTx()
        val hex = tx.serializeLegacy().toHex()

        val parsed = parseRawTx(hex)

        assertEquals(tx.txid(), parsed.txid)
        assertEquals(2, parsed.outputs.size)
        assertEquals(123_456L, parsed.outputs[0].value)
        assertArrayEquals(fakeScript(0x22), parsed.outputs[0].scriptPubKey)
        assertEquals(7_000L, parsed.outputs[1].value)
        assertArrayEquals(fakeScript(0x33), parsed.outputs[1].scriptPubKey)
    }

    @Test
    fun `parseRawTx formato SegWit com marker-flag-witness recalcula o mesmo txid que o legado`() {
        val tx = sampleTx()
        val hexWithWitness = serializeWithWitness(tx).toHex()

        val parsed = parseRawTx(hexWithWitness)

        // txid nunca inclui a witness (BIP141) — tem que bater exatamente
        // com o calculado a partir da serialização legada da mesma tx.
        assertEquals(tx.txid(), parsed.txid)
        assertEquals(2, parsed.outputs.size)
        assertEquals(123_456L, parsed.outputs[0].value)
        assertEquals(7_000L, parsed.outputs[1].value)
    }

    @Test
    fun `parseRawTx com valor de saida adulterado produz txid diferente do original`() {
        val original = sampleTx()
        val adulterada = original.copy(
            outputs = listOf(
                TxOut(value = 1_000L, scriptPubKey = fakeScript(0x22)), // valor menor que o real (123_456)
                original.outputs[1]
            )
        )

        val parsedOriginal = parseRawTx(original.serializeLegacy().toHex())
        val parsedAdulterada = parseRawTx(adulterada.serializeLegacy().toHex())

        // Um provedor que reporta o valor errado do output só consegue
        // fazer isso servindo bytes que recalculam pra um txid DIFERENTE
        // do esperado — é exatamente essa divergência que
        // UtxoValueVerifier detecta e rejeita.
        assert(parsedOriginal.txid != parsedAdulterada.txid)
    }

    @Test
    fun `parseRawTx rejeita flag SegWit invalida`() {
        val tx = sampleTx()
        val bytes = serializeWithWitness(tx)
        bytes[5] = 0x02 // flag inválida (só 0x01 é válido pelo BIP144)

        assertThrows(IllegalArgumentException::class.java) {
            parseRawTx(bytes.toHex())
        }
    }
}
