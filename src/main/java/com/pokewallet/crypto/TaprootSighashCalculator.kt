package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.varInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Taproot Sighash Calculator — BIP341 (key-path)
 *
 * Implementa APENAS:
 *  - key-path spend (BIP86), sem script tree (ext_flag = 0)
 *  - SIGHASH_DEFAULT (hash_type = 0x00)
 *  - sem annex
 *
 * Validado byte-a-byte contra o vetor de teste OFICIAL do BIP341
 * (bitcoin/bips, wallet-test-vectors.json, keyPathSpending[0].
 * inputSpending com txinIndex=4, hashType=0) — ver
 * TaprootSighashCalculatorTest.kt.
 */
object TaprootSighashCalculator {

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

        val buffer = ByteBuffer
            .allocate(256)
            .order(ByteOrder.LITTLE_ENDIAN)

        // Epoch (BIP341)
        buffer.put(0x00)

        // Sighash type — SIGHASH_DEFAULT = 0x00 (1 byte, não 4)
        buffer.put(0x00)

        // Version + Locktime
        buffer.putInt(tx.version)
        buffer.putInt(tx.lockTime.toInt())

        // hashPrevouts / hashAmounts / hashScriptPubKeys / hashSequences
        // (SIGHASH_DEFAULT não é ANYONECANPAY, então os 4 sempre entram)
        buffer.put(hashPrevouts(tx))
        buffer.put(hashAmounts(utxos))
        buffer.put(hashScriptPubKeys(utxos))
        buffer.put(hashSequences(tx))

        // hashOutputs (SIGHASH_DEFAULT não é NONE nem SINGLE, então sempre entra)
        buffer.put(hashOutputs(tx))

        // Spend type: (ext_flag << 1) + annex_present — key-path, sem annex, sem script = 0
        buffer.put(0x00)

        // Input index (sem ANYONECANPAY, é só o índice — não outpoint/amount/scriptPubKey/sequence)
        buffer.putInt(inputIndex)

        // Sem annex → hashAnnex OMITIDO (não é zero, é ausente)
        // ext_flag = 0 (key-path puro, sem script tree) → hashTapLeaf/key_version/codesep_pos OMITIDOS

        val msg = buffer.array().copyOf(buffer.position())
        return taggedHash("TapSighash", msg)
    }

    // =================================================
    // Hash helpers — SHA256 SIMPLES (não double-sha256;
    // BIP341 difere do BIP143/legacy nesse ponto)
    // =================================================

    private fun hashPrevouts(tx: UnsignedTransaction): ByteArray {
        val buf = ByteBuffer
            .allocate(tx.inputs.size * 36)
            .order(ByteOrder.LITTLE_ENDIAN)

        tx.inputs.forEach {
            buf.put(it.prevTxId)
            buf.putInt(it.prevIndex)
        }

        return sha256(buf.array())
    }

    private fun hashAmounts(utxos: List<TxOut>): ByteArray {
        val buf = ByteBuffer
            .allocate(utxos.size * 8)
            .order(ByteOrder.LITTLE_ENDIAN)

        utxos.forEach {
            buf.putLong(it.value)
        }

        return sha256(buf.array())
    }

    private fun hashScriptPubKeys(utxos: List<TxOut>): ByteArray {
        val out = ByteBuffer
            .allocate(4096)
            .order(ByteOrder.LITTLE_ENDIAN)

        utxos.forEach {
            out.put(varInt(it.scriptPubKey.size.toLong()))
            out.put(it.scriptPubKey)
        }

        return sha256(out.array().copyOf(out.position()))
    }

    private fun hashSequences(tx: UnsignedTransaction): ByteArray {
        val buf = ByteBuffer
            .allocate(tx.inputs.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        tx.inputs.forEach {
            buf.putInt(it.sequence.toInt())
        }

        return sha256(buf.array())
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

        return sha256(out.array().copyOf(out.position()))
    }

    // =================================================
    // Crypto helpers
    // =================================================

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray())
        return sha256(tagHash + tagHash + data)
    }
}
