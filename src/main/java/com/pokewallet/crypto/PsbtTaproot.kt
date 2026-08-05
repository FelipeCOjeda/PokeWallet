package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.int32LE
import com.pokewallet.crypto.ByteSerializer.int64LE
import com.pokewallet.crypto.ByteSerializer.readVarInt
import com.pokewallet.crypto.ByteSerializer.varInt
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * PSBT Taproot — key-path spend (BIP86)
 *
 * Responsável por:
 *  - Parser/serializer Base64 (BIP174 + BIP371, só os campos key-path)
 *  - Finalização do PSBT Taproot
 *  - Serialização da transação SegWit v1
 *
 * NÃO define TaprootPsbtInput (modelo isolado em outro arquivo)
 */
data class PsbtTaproot(
    val unsignedTx: UnsignedTransaction,
    val inputs: MutableList<TaprootPsbtInput>,
    val outputs: MutableList<PsbtOutput>
) {
    companion object {
        /**
         * Parser Base64 análogo a Psbt.parseBase64(), com os key-types
         * BIP371 (Taproot) em vez dos BIP174 originais (SegWit v0):
         * PSBT_IN_TAP_INTERNAL_KEY (0x17), PSBT_IN_TAP_BIP32_DERIVATION
         * (0x16), PSBT_OUT_TAP_BIP32_DERIVATION (0x07). Não parseia
         * PSBT_IN_TAP_KEY_SIG (0x13) — este app nunca serializa uma
         * assinatura DENTRO de um PSBT (ver nota em PsbtTaproot.serializeBase64()),
         * então nunca há uma pra ler de volta aqui.
         */
        fun parseBase64(psbtBase64: String): PsbtTaproot {
            val raw = Base64.getDecoder().decode(psbtBase64)
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(5)
            buffer.get(magic)
            require(magic.contentEquals(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))) {
                "Invalid PSBT magic bytes"
            }

            var unsignedTx: UnsignedTransaction? = null
            while (true) {
                val keyLen = readVarInt(buffer)
                if (keyLen == 0L) break
                val key = ByteArray(keyLen.toInt())
                buffer.get(key)
                val valueLen = readVarInt(buffer)
                val value = ByteArray(valueLen.toInt())
                buffer.get(value)
                when (key[0].toInt()) {
                    0x00 -> unsignedTx = UnsignedTransaction.parse(value)
                }
            }
            require(unsignedTx != null) { "PSBT missing unsigned transaction" }

            val inputs = mutableListOf<TaprootPsbtInput>()
            repeat(unsignedTx.inputs.size) {
                val input = TaprootPsbtInput()
                while (true) {
                    val keyLen = readVarInt(buffer)
                    if (keyLen == 0L) break
                    val key = ByteArray(keyLen.toInt())
                    buffer.get(key)
                    val valueLen = readVarInt(buffer)
                    val value = ByteArray(valueLen.toInt())
                    buffer.get(value)
                    when (key[0].toInt()) {
                        0x01 -> input.witnessUtxo = TxOut.parse(value)
                        0x17 -> input.tapInternalKey = value
                        0x16 -> {
                            // key = 0x16 + pubkey x-only (32 bytes) — só há uma chave
                            // por input neste app (single-sig), então basta guardar o valor.
                            input.tapBip32Derivation = TapBip32Derivation.parse(value)
                        }
                    }
                }
                inputs.add(input)
            }

            val outputs = mutableListOf<PsbtOutput>()
            repeat(unsignedTx.outputs.size) {
                val output = PsbtOutput()
                while (true) {
                    val keyLen = readVarInt(buffer)
                    if (keyLen == 0L) break
                    val key = ByteArray(keyLen.toInt())
                    buffer.get(key)
                    val valueLen = readVarInt(buffer)
                    val value = ByteArray(valueLen.toInt())
                    buffer.get(value)
                    when (key[0].toInt()) {
                        0x07 -> {
                            val xOnlyPubKey = key.copyOfRange(1, key.size)
                            output.tapBip32Derivations[xOnlyPubKey.toHex()] = TapBip32Derivation.parse(value)
                        }
                    }
                }
                outputs.add(output)
            }

            return PsbtTaproot(unsignedTx, inputs, outputs)
        }
    }

    /**
     * Serializa como PSBT base64 — mesmo escopo/limitação de
     * Psbt.serializeBase64() (só o PSBT NÃO-assinado que sai do
     * dispositivo watch-only; a volta é uma tx bruta finalizada, não um
     * PSBT parcialmente assinado — ver Fase C4/C5).
     */
    fun serializeBase64(): String {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))

        writeKeyValue(out, byteArrayOf(0x00), unsignedTx.serializeLegacy())
        out.write(0x00)

        inputs.forEach { input ->
            input.witnessUtxo?.let { writeKeyValue(out, byteArrayOf(0x01), it.serialize()) }
            input.tapInternalKey?.let { writeKeyValue(out, byteArrayOf(0x17), it) }
            input.tapBip32Derivation?.let { deriv ->
                val xOnlyPubKey = requireNotNull(input.tapInternalKey) {
                    "tapBip32Derivation sem tapInternalKey correspondente"
                }
                writeKeyValue(out, byteArrayOf(0x16) + xOnlyPubKey, deriv.serialize())
            }
            out.write(0x00)
        }

        outputs.forEach { output ->
            output.tapBip32Derivations.forEach { (xOnlyPubKeyHex, deriv) ->
                writeKeyValue(out, byteArrayOf(0x07) + xOnlyPubKeyHex.hexToBytes(), deriv.serialize())
            }
            out.write(0x00)
        }

        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}

/**
 * Finaliza o PSBT Taproot (key-path spend)
 *
 * Witness v1:
 *   <schnorr_signature>
 */
fun PsbtTaproot.finalize(): ByteArray {

    inputs.forEachIndexed { index, input ->

        require(input.witnessUtxo != null) {
            "Taproot input $index sem witnessUtxo"
        }

        require(input.tapKeySig != null) {
            "Taproot input $index sem tapKeySig"
        }

        // BIP86 key-path:
        // witness stack = [ schnorr_signature ]
        input.finalWitness = listOf(input.tapKeySig!!)
    }

    return serializeFinalTransaction()
}

/* ============================================================
 * Serialização da transação final — SegWit v1 (Taproot)
 * ============================================================
 */

private fun PsbtTaproot.serializeFinalTransaction(): ByteArray {

    val out = ByteArrayOutputStream()

    // Version
    out.write(int32LE(unsignedTx.version))

    // Marker + Flag
    out.write(0x00)
    out.write(0x01)

    // Inputs
    out.write(varInt(unsignedTx.inputs.size.toLong()))
    unsignedTx.inputs.forEach {
        out.write(it.prevTxId)
        out.write(int32LE(it.prevIndex))
        out.write(varInt(0)) // scriptSig vazio (Taproot)
        out.write(int32LE(it.sequence.toInt()))
    }

    // Outputs
    out.write(varInt(unsignedTx.outputs.size.toLong()))
    unsignedTx.outputs.forEach {
        out.write(int64LE(it.value))
        out.write(varInt(it.scriptPubKey.size.toLong()))
        out.write(it.scriptPubKey)
    }

    // Witnesses (SegWit v1)
    inputs.forEach { input ->
        val witness = input.finalWitness
            ?: error("Witness Taproot ausente")

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
