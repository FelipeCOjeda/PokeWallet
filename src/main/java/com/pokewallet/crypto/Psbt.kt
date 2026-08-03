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
 * PSBT — BIP174
 *
 * Implementa:
 *  - Parser Base64
 *  - Modelo de dados
 *  - Finalização (SegWit v0 / BIP84)
 */
data class Psbt(
    val unsignedTx: UnsignedTransaction,
    val inputs: MutableList<PsbtInput>,
    val outputs: MutableList<PsbtOutput>
) {

    companion object {

        /**
         * Parse PSBT from Base64 string
         */
        fun parseBase64(psbtBase64: String): Psbt {
            val raw = Base64.getDecoder().decode(psbtBase64)
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

            // Magic bytes: "psbt" + 0xff
            val magic = ByteArray(5)
            buffer.get(magic)
            require(
                magic.contentEquals(
                    byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
                )
            ) {
                "Invalid PSBT magic bytes"
            }

            // -------------------------------------------------
            // GLOBAL MAP
            // -------------------------------------------------

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

            require(unsignedTx != null) {
                "PSBT missing unsigned transaction"
            }

            // -------------------------------------------------
            // INPUT MAPS
            // -------------------------------------------------

            val inputs = mutableListOf<PsbtInput>()

            repeat(unsignedTx.inputs.size) {
                val input = PsbtInput()

                while (true) {
                    val keyLen = readVarInt(buffer)
                    if (keyLen == 0L) break

                    val key = ByteArray(keyLen.toInt())
                    buffer.get(key)

                    val valueLen = readVarInt(buffer)
                    val value = ByteArray(valueLen.toInt())
                    buffer.get(value)

                    when (key[0].toInt()) {
                        0x01 -> {
                            // witnessUtxo
                            input.witnessUtxo = TxOut.parse(value)
                        }

                        0x02 -> {
                            // partial signature
                            val pubKey = key.copyOfRange(1, key.size)
                            input.partialSignatures[pubKey.toHex()] = value
                        }
                    }
                }

                inputs.add(input)
            }

            // -------------------------------------------------
            // OUTPUT MAPS (ignorados por enquanto)
            // -------------------------------------------------

            val outputs = mutableListOf<PsbtOutput>()
            repeat(unsignedTx!!.outputs.size) {
                while (true) {
                    val keyLen = readVarInt(buffer)
                    if (keyLen == 0L) break

                    val valueLen = readVarInt(buffer)
                    buffer.position(buffer.position() + valueLen.toInt())
                }
                outputs.add(PsbtOutput())
            }

            return Psbt(
                unsignedTx = unsignedTx!!,
                inputs = inputs,
                outputs = outputs
            )
        }
    }

    /**
     * Finaliza o PSBT (BIP174)
     *
     * Constrói uma raw transaction SegWit (BIP84),
     * pronta para broadcast.
     */
    fun finalize(): ByteArray {

        inputs.forEachIndexed { index, input ->

            val utxo = input.witnessUtxo
                ?: error("Input $index não possui witnessUtxo")

            require(input.partialSignatures.isNotEmpty()) {
                "Input $index não possui assinatura"
            }

            require(input.partialSignatures.size == 1) {
                "Multisig ainda não suportado"
            }

            val (pubKeyHex, signature) =
                input.partialSignatures.entries.first()

            // witness stack: <sig> <pubkey>
            input.finalWitness = listOf(
                signature,
                pubKeyHex.hexToBytes()
            )

            input.partialSignatures.clear()
        }

        return serializeFinalTransaction()
    }

    /**
     * Txid da transação — double-sha256 da serialização SEM witness
     * (formato legado, pre-BIP144), com os bytes revertidos pra exibição
     * (convenção Bitcoin de txid em hex é big-endian, ao contrário da
     * serialização em si que é little-endian).
     *
     * Calculado localmente: não depende de nenhuma resposta de rede.
     */
    fun txid(): String = unsignedTx.txid()

    // -------------------------------------------------
    // Serialização da transação final (SegWit)
    // -------------------------------------------------

    private fun serializeFinalTransaction(): ByteArray {

        val out = ByteArrayOutputStream()

        // Version
        out.write(int32LE(unsignedTx.version))

        // Marker + Flag
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

        // Witnesses
        inputs.forEach { input ->
            val witness = input.finalWitness
                ?: error("Witness ausente")

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
}

/* ============================================================
 * INPUT / OUTPUT MODELS
 * ============================================================
 */

class PsbtInput {
    var witnessUtxo: TxOut? = null
    // Chave = hex do pubkey. ByteArray como chave de Map compara por
    // identidade de objeto, não por conteúdo — dois pubkeys com os mesmos
    // bytes seriam tratados como entradas diferentes.
    val partialSignatures: MutableMap<String, ByteArray> = mutableMapOf()
    var finalWitness: List<ByteArray>? = null
}

class PsbtOutput

/* ============================================================
 * TRANSACTION MODELS
 * ============================================================
 */

data class UnsignedTransaction(
    val version: Int,
    val inputs: List<TxIn>,
    val outputs: List<TxOut>,
    val lockTime: Long
) {

    companion object {
        fun parse(raw: ByteArray): UnsignedTransaction {
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

            val version = buf.int
            val inputCount = readVarInt(buf)

            val inputs = mutableListOf<TxIn>()
            repeat(inputCount.toInt()) {
                inputs.add(TxIn.parse(buf))
            }

            val outputCount = readVarInt(buf)
            val outputs = mutableListOf<TxOut>()
            repeat(outputCount.toInt()) {
                outputs.add(TxOut.parse(buf))
            }

            val lockTime = buf.int.toLong() and 0xffffffffL

            return UnsignedTransaction(
                version = version,
                inputs = inputs,
                outputs = outputs,
                lockTime = lockTime
            )
        }
    }
}

/**
 * Txid — double-sha256 da serialização legada (sem witness), com os
 * bytes revertidos pra exibição (convenção Bitcoin de txid em hex é
 * big-endian). scriptSig sempre vazio: nem SegWit v0 nem v1 (Taproot)
 * usam scriptSig, e para o único caso que usa (P2SH-wrapped) este app
 * não implementa suporte.
 *
 * Independe de PSBT/tipo de gasto — usada tanto por Psbt.txid() (BIP84)
 * quanto pelo caminho Taproot (BIP86), que não tem uma classe Psbt própria
 * com esse método.
 */
fun UnsignedTransaction.txid(): String {
    val out = ByteArrayOutputStream()

    out.write(int32LE(version))

    out.write(varInt(inputs.size.toLong()))
    inputs.forEach { input ->
        out.write(input.prevTxId)
        out.write(int32LE(input.prevIndex))
        out.write(varInt(0)) // scriptSig vazio
        out.write(int32LE(input.sequence.toInt()))
    }

    out.write(varInt(outputs.size.toLong()))
    outputs.forEach { output ->
        out.write(int64LE(output.value))
        out.write(varInt(output.scriptPubKey.size.toLong()))
        out.write(output.scriptPubKey)
    }

    out.write(int32LE(lockTime.toInt()))

    return CryptoUtils.doubleSha256(out.toByteArray()).reversedArray().toHex()
}

data class TxIn(
    val prevTxId: ByteArray,
    val prevIndex: Int,
    val scriptSig: ByteArray,
    val sequence: Long
) {
    companion object {
        fun parse(buf: ByteBuffer): TxIn {
            val txid = ByteArray(32)
            buf.get(txid)

            val index = buf.int
            val scriptLen = readVarInt(buf)
            val script = ByteArray(scriptLen.toInt())
            buf.get(script)

            val seq = buf.int.toLong() and 0xffffffffL

            return TxIn(txid, index, script, seq)
        }
    }
}

data class TxOut(
    val value: Long,
    val scriptPubKey: ByteArray
) {
    companion object {
        fun parse(buf: ByteBuffer): TxOut {
            val value = buf.long
            val scriptLen = readVarInt(buf)
            val script = ByteArray(scriptLen.toInt())
            buf.get(script)
            return TxOut(value, script)
        }

        fun parse(raw: ByteArray): TxOut =
            parse(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN))
    }
}
