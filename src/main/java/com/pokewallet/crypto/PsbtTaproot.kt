package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.int32LE
import com.pokewallet.crypto.ByteSerializer.int64LE
import com.pokewallet.crypto.ByteSerializer.varInt
import java.io.ByteArrayOutputStream

/**
 * PSBT Taproot — key-path spend (BIP86)
 *
 * Responsável APENAS por:
 *  - Finalização do PSBT Taproot
 *  - Serialização da transação SegWit v1
 *
 * NÃO define TaprootPsbtInput (modelo isolado em outro arquivo)
 */
data class PsbtTaproot(
    val unsignedTx: UnsignedTransaction,
    val inputs: MutableList<TaprootPsbtInput>,
    val outputs: MutableList<PsbtOutput>
)

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
