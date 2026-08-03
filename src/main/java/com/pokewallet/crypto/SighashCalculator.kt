package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.int32LE
import com.pokewallet.crypto.ByteSerializer.int64LE
import com.pokewallet.crypto.ByteSerializer.varInt
import java.io.ByteArrayOutputStream

object SighashCalculator {

    const val SIGHASH_ALL = 0x01

    // =================================================
    // BIP143 — SegWit v0 (P2WPKH / BIP84)
    // =================================================

    fun segwitV0Sighash(
        tx: UnsignedTransaction,
        inputIndex: Int,
        scriptCode: ByteArray,
        inputValue: Long,
        sighashType: Int = SIGHASH_ALL
    ): ByteArray {

        val out = ByteArrayOutputStream()

        // version
        out.write(int32LE(tx.version))

        // hashPrevouts
        out.write(doubleSha256(serializePrevouts(tx)))

        // hashSequence
        out.write(doubleSha256(serializeSequences(tx)))

        // outpoint
        val input = tx.inputs[inputIndex]
        out.write(input.prevTxId)
        out.write(int32LE(input.prevIndex))

        // scriptCode
        out.write(varInt(scriptCode.size.toLong()))
        out.write(scriptCode)

        // value
        out.write(int64LE(inputValue))

        // sequence
        out.write(int32LE(input.sequence.toInt()))

        // hashOutputs
        out.write(doubleSha256(serializeOutputs(tx)))

        // locktime
        out.write(int32LE(tx.lockTime.toInt()))

        // sighash type
        out.write(int32LE(sighashType))

        return doubleSha256(out.toByteArray())
    }

    // =================================================
    // BIP341 — Taproot Key Path (BIP86)
    // =================================================

    fun taprootKeyPathSighash(
        tx: UnsignedTransaction,
        inputIndex: Int,
        prevOutputs: List<TxOut>,
        sighashType: Int = 0x00 // DEFAULT
    ): ByteArray {

        val out = ByteArrayOutputStream()

        // epoch
        out.write(0x00)

        // sighash type
        out.write(sighashType)

        // version
        out.write(int32LE(tx.version))

        // locktime
        out.write(int32LE(tx.lockTime.toInt()))

        // hashPrevouts
        out.write(taggedHash("TapSighash", serializePrevouts(tx)))

        // hashAmounts
        out.write(taggedHash("TapSighash", serializeAmounts(prevOutputs)))

        // hashScriptPubKeys
        out.write(taggedHash("TapSighash", serializeScriptPubKeys(prevOutputs)))

        // hashSequences
        out.write(taggedHash("TapSighash", serializeSequences(tx)))

        // hashOutputs
        out.write(taggedHash("TapSighash", serializeOutputs(tx)))

        // input index
        out.write(int32LE(inputIndex))

        return taggedHash("TapSighash", out.toByteArray())
    }

    // =================================================
    // Serialization helpers
    // =================================================

    private fun serializePrevouts(tx: UnsignedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.inputs.forEach {
            out.write(it.prevTxId)
            out.write(int32LE(it.prevIndex))
        }
        return out.toByteArray()
    }

    private fun serializeSequences(tx: UnsignedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.inputs.forEach {
            out.write(int32LE(it.sequence.toInt()))
        }
        return out.toByteArray()
    }

    private fun serializeOutputs(tx: UnsignedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.outputs.forEach {
            out.write(int64LE(it.value))
            out.write(varInt(it.scriptPubKey.size.toLong()))
            out.write(it.scriptPubKey)
        }
        return out.toByteArray()
    }

    private fun serializeAmounts(utxos: List<TxOut>): ByteArray {
        val out = ByteArrayOutputStream()
        utxos.forEach {
            out.write(int64LE(it.value))
        }
        return out.toByteArray()
    }

    private fun serializeScriptPubKeys(utxos: List<TxOut>): ByteArray {
        val out = ByteArrayOutputStream()
        utxos.forEach {
            out.write(varInt(it.scriptPubKey.size.toLong()))
            out.write(it.scriptPubKey)
        }
        return out.toByteArray()
    }

    // =================================================
    // Hash helpers
    // =================================================

    private fun doubleSha256(data: ByteArray): ByteArray =
        CryptoUtils.sha256(CryptoUtils.sha256(data))

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = CryptoUtils.sha256(tag.toByteArray())
        return CryptoUtils.sha256(tagHash + tagHash + data)
    }
}
