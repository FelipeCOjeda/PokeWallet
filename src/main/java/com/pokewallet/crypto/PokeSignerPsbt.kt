package com.pokewallet.crypto

import java.util.Base64

/** Finaliza o PSBT que voltou assinado do PokeSigner → hex da tx pronta. */
object PokeSignerPsbt {
    fun finalizeSigned(signed: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(signed)
        val seg = Psbt.parseBase64(b64)
        val spk = seg.inputs.firstOrNull()?.witnessUtxo?.scriptPubKey
            ?: error("PSBT assinado sem witness_utxo")
        val isTaproot = spk.size == 34 && spk[0] == 0x51.toByte()
        val raw = if (isTaproot) PsbtTaproot.parseBase64(b64).finalize() else seg.finalize()
        return raw.toHex()
    }
}
