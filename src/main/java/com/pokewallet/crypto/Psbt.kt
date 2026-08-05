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

                        0x06 -> {
                            // PSBT_IN_BIP32_DERIVATION — key = 0x06 + pubkey comprimido (33 bytes)
                            val pubKey = key.copyOfRange(1, key.size)
                            input.bip32Derivations[pubKey.toHex()] = Bip32Derivation.parse(value)
                        }
                    }
                }

                inputs.add(input)
            }

            // -------------------------------------------------
            // OUTPUT MAPS
            // -------------------------------------------------

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
                        0x02 -> {
                            // PSBT_OUT_BIP32_DERIVATION — key = 0x02 + pubkey comprimido (33 bytes)
                            val pubKey = key.copyOfRange(1, key.size)
                            output.bip32Derivations[pubKey.toHex()] = Bip32Derivation.parse(value)
                        }
                    }
                }

                outputs.add(output)
            }

            return Psbt(
                unsignedTx = unsignedTx,
                inputs = inputs,
                outputs = outputs
            )
        }
    }

    /**
     * Serializa como PSBT base64 (BIP174) — só o necessário pro fluxo
     * air-gapped deste app: mapa global (tx não-assinada), witness_utxo +
     * bip32_derivation por input, bip32_derivation só no output de troco
     * (destino externo não é chave desta carteira, nada a declarar).
     * NÃO serializa assinatura/finalização — esse método só é chamado
     * pra montar o PSBT NÃO-assinado que sai do dispositivo watch-only; o
     * caminho de volta (dispositivo com a seed já assinou) devolve uma tx
     * bruta finalizada, não um PSBT (ver Fase C4/C5 — single-sig permite
     * finalizar num passo só, não precisa round-trip de PSBT parcialmente
     * assinado).
     */
    fun serializeBase64(): String {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))

        writeKeyValue(out, byteArrayOf(0x00), unsignedTx.serializeLegacy())
        out.write(0x00)

        inputs.forEach { input ->
            input.witnessUtxo?.let { writeKeyValue(out, byteArrayOf(0x01), it.serialize()) }
            input.bip32Derivations.forEach { (pubKeyHex, deriv) ->
                writeKeyValue(out, byteArrayOf(0x06) + pubKeyHex.hexToBytes(), deriv.serialize())
            }
            out.write(0x00)
        }

        outputs.forEach { output ->
            output.bip32Derivations.forEach { (pubKeyHex, deriv) ->
                writeKeyValue(out, byteArrayOf(0x02) + pubKeyHex.hexToBytes(), deriv.serialize())
            }
            out.write(0x00)
        }

        return Base64.getEncoder().encodeToString(out.toByteArray())
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
    /** PSBT_IN_BIP32_DERIVATION (0x06) — chave = hex do pubkey comprimido. */
    val bip32Derivations: MutableMap<String, Bip32Derivation> = mutableMapOf()
}

/**
 * Compartilhado entre Psbt (BIP84) e PsbtTaproot (BIP86) — carrega os dois
 * tipos de bip32_derivation porque um output pode ser de qualquer um dos
 * dois spend types dependendo de qual Psbt* o está usando; só um dos dois
 * mapas é populado de fato em cada caso concreto.
 */
class PsbtOutput {
    /** PSBT_OUT_BIP32_DERIVATION (0x02) — SegWit v0, chave = hex do pubkey comprimido. */
    val bip32Derivations: MutableMap<String, Bip32Derivation> = mutableMapOf()
    /** PSBT_OUT_TAP_BIP32_DERIVATION (0x07, BIP371) — Taproot, chave = hex do pubkey x-only. */
    val tapBip32Derivations: MutableMap<String, TapBip32Derivation> = mutableMapOf()
}

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
 * Serialização legada (sem marker/flag/witness) — é tanto a base do
 * cálculo de txid (double-sha256 dela) quanto o próprio formato exigido
 * pelo campo PSBT_GLOBAL_UNSIGNED_TX (BIP174: a tx não-assinada no mapa
 * global é sempre serializada nesse formato legado, mesmo numa tx que vai
 * acabar sendo SegWit). scriptSig sempre vazio: nem SegWit v0 nem v1
 * (Taproot) usam scriptSig, e o único caso que usa (P2SH-wrapped) este
 * app não implementa suporte.
 */
fun UnsignedTransaction.serializeLegacy(): ByteArray {
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

    return out.toByteArray()
}

/**
 * Txid — double-sha256 da serialização legada (sem witness), com os
 * bytes revertidos pra exibição (convenção Bitcoin de txid em hex é
 * big-endian).
 *
 * Independe de PSBT/tipo de gasto — usada tanto por Psbt.txid() (BIP84)
 * quanto pelo caminho Taproot (BIP86), que não tem uma classe Psbt própria
 * com esse método.
 */
fun UnsignedTransaction.txid(): String =
    CryptoUtils.doubleSha256(serializeLegacy()).reversedArray().toHex()

/**
 * Extrai o txid de uma tx bruta assinada (formato com marker/flag/witness,
 * BIP144) — usado no lado watch-only do fluxo air-gapped (Fase C5) pra
 * conferir, ANTES de transmitir, que a tx que voltou do aparelho signer é
 * exatamente a que foi pedida pra assinar. Funciona porque assinar nunca
 * muda o txid (ele é calculado sobre a serialização SEM witness) — então
 * o txid calculado aqui, depois de assinada, tem que bater exatamente com
 * o expectedTxid calculado ANTES de sair como PSBT pra assinar. Se um bug
 * no parsing do signer, um QR corrompido ou um toque errado tivesse
 * trocado destino/valor, o txid recalculado aqui seria diferente — só
 * transmite se bater.
 */
fun parseSignedTxidHex(rawTxHex: String): String {
    val raw = rawTxHex.hexToBytes()
    val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

    val version = buf.int
    val marker = buf.get()
    val flag = buf.get()
    require(marker == 0x00.toByte() && flag == 0x01.toByte()) {
        "Tx sem marker/flag SegWit (0x00 0x01) — formato inesperado"
    }

    val inputCount = readVarInt(buf)
    val inputs = (0 until inputCount).map {
        val txid = ByteArray(32).also { b -> buf.get(b) }
        val prevIndex = buf.int
        val scriptLen = readVarInt(buf)
        buf.position(buf.position() + scriptLen.toInt()) // scriptSig (sempre vazio nas txs deste app)
        val sequence = buf.int.toLong() and 0xffffffffL
        TxIn(txid, prevIndex, byteArrayOf(), sequence)
    }

    val outputCount = readVarInt(buf)
    val outputs = (0 until outputCount).map {
        val value = buf.long
        val scriptLen = readVarInt(buf)
        val script = ByteArray(scriptLen.toInt()).also { b -> buf.get(b) }
        TxOut(value, script)
    }

    // Witnesses — pula, o conteúdo não entra no cálculo do txid.
    repeat(inputCount.toInt()) {
        val witnessCount = readVarInt(buf)
        repeat(witnessCount.toInt()) {
            val len = readVarInt(buf)
            buf.position(buf.position() + len.toInt())
        }
    }

    val lockTime = buf.int.toLong() and 0xffffffffL

    return UnsignedTransaction(version, inputs, outputs, lockTime).txid()
}

/** key||value de um par PSBT (BIP174: varint(len)+bytes pra cada um). */
internal fun writeKeyValue(out: ByteArrayOutputStream, key: ByteArray, value: ByteArray) {
    out.write(varInt(key.size.toLong()))
    out.write(key)
    out.write(varInt(value.size.toLong()))
    out.write(value)
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

    /** Formato PSBT_IN_WITNESS_UTXO (BIP174): igual a um TxOut de tx normal. */
    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(int64LE(value))
        out.write(varInt(scriptPubKey.size.toLong()))
        out.write(scriptPubKey)
        return out.toByteArray()
    }
}
