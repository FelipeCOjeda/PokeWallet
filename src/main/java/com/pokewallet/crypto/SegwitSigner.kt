package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.int32LE
import com.pokewallet.crypto.ByteSerializer.int64LE
import com.pokewallet.crypto.ByteSerializer.varInt
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object SegwitSigner {

    private const val SIGHASH_ALL = 0x01

    fun sign(
        unsignedTx: UnsignedTransaction,
        inputIndex: Int,
        utxoValue: Long,
        scriptPubKey: ByteArray,
        privateKey: ByteArray
    ): ByteArray {

        val hashPrevouts = hashPrevouts(unsignedTx)
        val hashSequence = hashSequence(unsignedTx)
        val hashOutputs = hashOutputs(unsignedTx)

        val input = unsignedTx.inputs[inputIndex]

        // scriptCode = legacy P2PKH
        val pubKeyHash = scriptPubKey.copyOfRange(2, 22)
        val scriptCode = buildP2PKHScript(pubKeyHash)

        val preimage = ByteArrayOutputStream()

        // version
        preimage.write(int32LE(unsignedTx.version))

        // hashPrevouts
        preimage.write(hashPrevouts)

        // hashSequence
        preimage.write(hashSequence)

        // outpoint
        preimage.write(input.prevTxId)
        preimage.write(int32LE(input.prevIndex))

        // scriptCode
        preimage.write(varInt(scriptCode.size.toLong()))
        preimage.write(scriptCode)

        // value
        preimage.write(int64LE(utxoValue))

        // sequence
        preimage.write(int32LE(input.sequence.toInt()))

        // hashOutputs
        preimage.write(hashOutputs)

        // locktime
        preimage.write(int32LE(unsignedTx.lockTime.toInt()))

        // sighash type
        preimage.write(int32LE(SIGHASH_ALL))

        val sighash = sha256d(preimage.toByteArray())

        val derSignature = Secp256k1.sign(privateKey, sighash)

        // DER + sighash byte
        return derSignature + byteArrayOf(SIGHASH_ALL.toByte())
    }

    // -------------------------------------------------
    // Hash helpers (BIP143)
    // -------------------------------------------------

    private fun hashPrevouts(tx: UnsignedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.inputs.forEach {
            out.write(it.prevTxId)
            out.write(int32LE(it.prevIndex))
        }
        return sha256d(out.toByteArray())
    }

    private fun hashSequence(tx: UnsignedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.inputs.forEach {
            out.write(int32LE(it.sequence.toInt()))
        }
        return sha256d(out.toByteArray())
    }

    private fun hashOutputs(tx: UnsignedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.outputs.forEach {
            out.write(int64LE(it.value))
            out.write(varInt(it.scriptPubKey.size.toLong()))
            out.write(it.scriptPubKey)
        }
        return sha256d(out.toByteArray())
    }

    // -------------------------------------------------
    // Script helpers
    // -------------------------------------------------

    private fun buildP2PKHScript(pubKeyHash: ByteArray): ByteArray =
        byteArrayOf(
            0x76,       // OP_DUP
            0xa9.toByte(), // OP_HASH160
            0x14        // push 20 bytes
        ) + pubKeyHash + byteArrayOf(
            0x88.toByte(), // OP_EQUALVERIFY
            0xac.toByte()  // OP_CHECKSIG
        )

    // -------------------------------------------------
    // Crypto helpers
    // -------------------------------------------------

    private fun sha256d(data: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(sha256.digest(data))
    }
}
