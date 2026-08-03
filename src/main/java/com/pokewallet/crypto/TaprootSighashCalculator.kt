package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.int32LE
import com.pokewallet.crypto.ByteSerializer.int64LE
import com.pokewallet.crypto.ByteSerializer.varInt
import java.io.ByteArrayOutputStream
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

        val out = ByteArrayOutputStream()

        // Epoch (BIP341)
        out.write(0x00)

        // Sighash type — SIGHASH_DEFAULT = 0x00 (1 byte, não 4)
        out.write(0x00)

        // Version + Locktime
        out.write(int32LE(tx.version))
        out.write(int32LE(tx.lockTime.toInt()))

        // hashPrevouts / hashAmounts / hashScriptPubKeys / hashSequences
        // (SIGHASH_DEFAULT não é ANYONECANPAY, então os 4 sempre entram)
        out.write(hashPrevouts(tx))
        out.write(hashAmounts(utxos))
        out.write(hashScriptPubKeys(utxos))
        out.write(hashSequences(tx))

        // hashOutputs (SIGHASH_DEFAULT não é NONE nem SINGLE, então sempre entra)
        out.write(hashOutputs(tx))

        // Spend type: (ext_flag << 1) + annex_present — key-path, sem annex, sem script = 0
        out.write(0x00)

        // Input index (sem ANYONECANPAY, é só o índice — não outpoint/amount/scriptPubKey/sequence)
        out.write(int32LE(inputIndex))

        // Sem annex → hashAnnex OMITIDO (não é zero, é ausente)
        // ext_flag = 0 (key-path puro, sem script tree) → hashTapLeaf/key_version/codesep_pos OMITIDOS

        return taggedHash("TapSighash", out.toByteArray())
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
        val out = ByteArrayOutputStream()

        utxos.forEach {
            out.write(varInt(it.scriptPubKey.size.toLong()))
            out.write(it.scriptPubKey)
        }

        return sha256(out.toByteArray())
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
        val out = ByteArrayOutputStream()

        tx.outputs.forEach {
            out.write(int64LE(it.value))
            out.write(varInt(it.scriptPubKey.size.toLong()))
            out.write(it.scriptPubKey)
        }

        return sha256(out.toByteArray())
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
