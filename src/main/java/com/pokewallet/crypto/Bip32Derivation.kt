package com.pokewallet.crypto

import com.pokewallet.crypto.ByteSerializer.int32LE
import com.pokewallet.crypto.ByteSerializer.readVarInt
import com.pokewallet.crypto.ByteSerializer.varInt
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Valor do campo BIP174 PSBT_IN_BIP32_DERIVATION (0x06, input) /
 * PSBT_OUT_BIP32_DERIVATION (0x02, output) — usado nos caminhos SegWit v0
 * (BIP84). Formato: 4 bytes de fingerprint da chave MESTRA (não confundir
 * com o fingerprint do pai imediato que fica embutido num xpub) + N passos
 * de derivação (cada um uint32 little-endian, com o bit 31 setado quando
 * hardened — ver KeyDerivation.hardened()).
 */
data class Bip32Derivation(val masterFingerprint: ByteArray, val path: List<Int>) {
    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(masterFingerprint)
        path.forEach { out.write(int32LE(it)) }
        return out.toByteArray()
    }

    companion object {
        fun parse(value: ByteArray): Bip32Derivation {
            require(value.size >= 4 && (value.size - 4) % 4 == 0) {
                "bip32_derivation com tamanho inválido: ${value.size} bytes"
            }
            val fingerprint = value.copyOfRange(0, 4)
            val path = mutableListOf<Int>()
            var i = 4
            while (i < value.size) {
                path += ByteBuffer.wrap(value, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
                i += 4
            }
            return Bip32Derivation(fingerprint, path)
        }
    }
}

/**
 * Valor dos campos BIP371 PSBT_IN_TAP_BIP32_DERIVATION (0x16, input) /
 * PSBT_OUT_TAP_BIP32_DERIVATION (0x07, output) — caminho Taproot (BIP86).
 * Formato: compact-size com a contagem de leaf hashes + os hashes (32
 * bytes cada) + fingerprint da chave mestra + passos de derivação (mesmo
 * formato de [Bip32Derivation]). leafHashes sempre vazio neste app — só
 * key-path spend é suportado, não há tapscript/leaf script em lugar
 * nenhum do código de assinatura.
 */
data class TapBip32Derivation(
    val leafHashes: List<ByteArray>,
    val masterFingerprint: ByteArray,
    val path: List<Int>
) {
    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varInt(leafHashes.size.toLong()))
        leafHashes.forEach { out.write(it) }
        out.write(masterFingerprint)
        path.forEach { out.write(int32LE(it)) }
        return out.toByteArray()
    }

    companion object {
        fun parse(value: ByteArray): TapBip32Derivation {
            val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
            val leafCount = readVarInt(buf)
            val leafHashes = (0 until leafCount).map {
                ByteArray(32).also { h -> buf.get(h) }
            }
            val fingerprint = ByteArray(4).also { buf.get(it) }
            val path = mutableListOf<Int>()
            while (buf.remaining() >= 4) path += buf.int
            return TapBip32Derivation(leafHashes, fingerprint, path)
        }
    }
}
