package com.pokewallet.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Taproot Sighash Calculator — BIP341 (key-path)
 *
 * Implementa APENAS:
 *  - key-path spend (BIP86)
 *  - SIGHASH_DEFAULT
 *
 * NÃO suporta:
 *  - script-path
 *  - annex
 *  - ANYONECANPAY
 *  - outros sighash flags
 */
object TaprootSighashCalculator {

    private val ZERO32 = ByteArray(32) { 0x00 }

    /**
     * Calcula o sighash Taproot (BIP341)
     *
     * Retorna:
     *  - 32-byte message digest (para Schnorr / BIP340)
     */
    fun calculate(
        tx: UnsignedTransaction,
        inputIndex: Int,
        utxos: List<TxOut>
    ): ByteArray {

        require(utxos.size == tx.inputs.size) {
            "Lista de UTXOs deve corresponder aos inputs"
        }

        val taggedHash = taggedHash("TapSighash")

        val buffer = ByteBuffer
            .allocate(4096)
            .order(ByteOrder.LITTLE_ENDIAN)

        // =================================================
        // Epoch (BIP341)
        // =================================================
        buffer.put(0x00)

        // =================================================
        // Sighash type
        // BIP341: SIGHASH_DEFAULT = 0x00
        // (não é serializado no witness)
        // =================================================
        buffer.putInt(0)

        // =================================================
        // Version + Locktime
        // =================================================
        buffer.putInt(tx.version)
        buffer.putInt(tx.lockTime.toInt())

        // =================================================
        // hashPrevouts
        // =================================================
        buffer.put(hashPrevouts(tx))

        // =================================================
        // hashAmounts
        // =================================================
        buffer.put(hashAmounts(utxos))

        // =================================================
        // hashScriptPubKeys
        // =================================================
        buffer.put(hashScriptPubKeys(utxos))

        // =================================================
        // hashSequences
        // =================================================
        buffer.put(hashSequences(tx))

        // =================================================
        // hashOutputs
        // =================================================
        buffer.put(hashOutputs(tx))

        // =================================================
        // Spend type
        // 0x00 = key-path spend
        // =================================================
        buffer.put(0x00)

        // =================================================
        // Input index
        // =================================================
        buffer.putInt(inputIndex)

        // =================================================
        // hashAnnex (nenhum annex)
        // =================================================
        buffer.put(ZERO32)

        // =================================================
        // hashTapLeaf
        // key-path → ZERO32
        // =================================================
        buffer.put(ZERO32)

        // =================================================
        // key version + code separator pos
        // (somente script-path → zeros)
        // =================================================
        buffer.putInt(0)
        buffer.putInt(0xffffffff.toInt())

        val msg = buffer.array().copyOf(buffer.position())
        return taggedHash(msg)
    }

    // =================================================
    // Hash helpers
    // =================================================

    private fun hashPrevouts(tx: UnsignedTransaction): ByteArray {
        val buf = ByteBuffer
            .allocate(tx.inputs.size * 36)
            .order(ByteOrder.LITTLE_ENDIAN)

        tx.inputs.forEach {
            buf.put(it.prevTxId)
            buf.putInt(it.prevIndex)
        }

        return sha256d(buf.array())
    }

    private fun hashAmounts(utxos: List<TxOut>): ByteArray {
        val buf = ByteBuffer
            .allocate(utxos.size * 8)
            .order(ByteOrder.LITTLE_ENDIAN)

        utxos.forEach {
            buf.putLong(it.value)
        }

        return sha256d(buf.array())
    }

    private fun hashScriptPubKeys(utxos: List<TxOut>): ByteArray {
        val out = ByteBuffer
            .allocate(4096)
            .order(ByteOrder.LITTLE_ENDIAN)

        utxos.forEach {
            out.put(varInt(it.scriptPubKey.size.toLong()))
            out.put(it.scriptPubKey)
        }

        return sha256d(out.array().copyOf(out.position()))
    }

    private fun hashSequences(tx: UnsignedTransaction): ByteArray {
        val buf = ByteBuffer
            .allocate(tx.inputs.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        tx.inputs.forEach {
            buf.putInt(it.sequence.toInt())
        }

        return sha256d(buf.array())
    }

    private fun hashOutputs(tx: UnsignedTransaction): ByteArray {
        val out = ByteBuffer
            .allocate(4096)
            .order(ByteOrder.LITTLE_ENDIAN)

        tx.outputs.forEach {
            out.putLong(it.value)
            out.put(varInt(it.scriptPubKey.size.toLong()))
            out.put(it.scriptPubKey)
        }

        return sha256d(out.array().copyOf(out.position()))
    }

    // =================================================
    // Crypto helpers
    // =================================================

    private fun sha256d(data: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(sha256.digest(data))
    }

    private fun taggedHash(tag: String): (ByteArray) -> ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val tagHash = sha256.digest(tag.toByteArray())

        return { msg ->
            sha256.digest(tagHash + tagHash + msg)
        }
    }

    // =================================================
    // Serialization helpers
    // =================================================

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
        ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort())
            .array()

    private fun int32LE(value: Int): ByteArray =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private fun int64LE(value: Long): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value)
            .array()
}
