package com.pokewallet.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PSBT Taproot — BIP340 / BIP341 / BIP342
 *
 * Implementa:
 *  - PSBT input fields específicos de Taproot
 *  - Key-path spend (BIP86)
 *  - Finalização witness v1
 *
 * NÃO suporta:
 *  - script-path (por enquanto)
 *  - annex
 *  - tapscript
 */
data class PsbtTaproot(
    val unsignedTx: UnsignedTransaction,
    val inputs: MutableList<TaprootPsbtInput>,
    val outputs: MutableList<PsbtOutput>
) {

    /**
     * Finaliza o PSBT Taproot (key-path spend)
     *
     * Produz uma raw transaction SegWit v1 (BIP341),
     * pronta para broadcast.
     */
    fun finalize(): ByteArray {

        inputs.forEachIndexed { index, input ->

            require(input.witnessUtxo != null) {
                "Input $index sem witnessUtxo"
            }

            require(input.schnorrSignature != null) {
                "Input $index sem assinatura Schnorr"
            }

            // Taproot key-path:
            // witness stack = <schnorr_signature>
            input.finalWitness = listOf(
                input.schnorrSignature!!
            )
        }

        return serializeFinalTransaction()
    }

    // -------------------------------------------------
    // Serialização da transação final (SegWit v1)
    // -------------------------------------------------

    private fun serializeFinalTransaction(): ByteArray {

        val out = ByteArrayOutputStream()

        // Version
        out.write(int32LE(unsignedTx.version))

        // Marker + Flag (SegWit)
        out.write(0x00)
        out.write(0x01)

        // Inputs
        out.write(varInt(unsignedTx.inputs.size.toLong()))

        unsignedTx.inputs.forEach { input ->
            out.write(input.prevTxId)
            out.write(int32LE(input.prevIndex))
            out.write(varInt(0)) // scriptSig vazio
            out.write(int32LE(input.sequence.toInt()))
        }

        // Outputs
        out.write(varInt(unsignedTx.outputs.size.toLong()))

        unsignedTx.outputs.forEach { output ->
            out.write(int64LE(output.value))
            out.write(varInt(output.scriptPubKey.size.toLong()))
            out.write(output.scriptPubKey)
        }

        // Witnesses (Taproot)
        inputs.forEach { input ->
            val witness = input.finalWitness
                ?: error("Witness ausente em input Taproot")

            out.write(varInt(witness.size.toLong()))
            witness.forEach {
                out.write(varInt(it.size.toLong()))
                out.write(it)
            }
        }

        // Locktime
        out.write(int32LE(unsignedTx.lockTime.toInt()))

        return out.toByteArray()
    }

    // -------------------------------------------------
    // Helpers de serialização
    // -------------------------------------------------

    private fun varInt(value: Long): ByteArray =
        when {
            value < 0xfd -> byteArrayOf(value.toByte())
            value <= 0xffff ->
                byteArrayOf(0xfd.toByte()) + int16LE(value.toInt())
            value <= 0xffffffffL ->
                byteArrayOf(0xfe.toByte()) + int32LE(value.toInt())
            else ->
                byteArrayOf(0xff.toByte()) + int64LE(value)
        }

    private fun int16LE(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort()).array()

    private fun int32LE(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value).array()

    private fun int64LE(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value).array()
}

/* ============================================================
 * TAPROOT INPUT MODEL
 * ============================================================
 */

class TaprootPsbtInput {

    /** witnessUtxo obrigatório (BIP341) */
    var witnessUtxo: TxOut? = null

    /** chave interna x-only (32 bytes) — opcional para validação futura */
    var internalKey: ByteArray? = null

    /** assinatura Schnorr (64 bytes [+ sighash opcional]) */
    var schnorrSignature: ByteArray? = null

    /** witness final (key-path = 1 item) */
    var finalWitness: List<ByteArray>? = null
}
