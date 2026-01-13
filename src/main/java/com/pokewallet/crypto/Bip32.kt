package com.pokewallet.crypto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Bip32 {

    private val CURVE_N = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
        16
    )

    private const val HARDENED_OFFSET: Int = 0x80000000.toInt()

    fun fromSeed(seed: ByteArray): HDKey {
        val key = "Bitcoin seed".toByteArray()
        val i = CryptoUtils.hmacSha512(key, seed)

        val il = i.copyOfRange(0, 32)
        val ir = i.copyOfRange(32, 64)

        return HDKey(
            privateKey = il,
            chainCode = ir,
            depth = 0,
            index = 0,
            parentFingerprint = 0
        )
    }

    fun deriveChild(
        parent: HDKey,
        index: Int,
        hardened: Boolean
    ): HDKey {

        val idx = if (hardened) {
            index or HARDENED_OFFSET
        } else {
            index
        }

        val data = ByteArrayOutputStream()

        if (hardened) {
            // 0x00 || ser256(kpar)
            data.write(0x00)
            data.write(parent.privateKey)
        } else {
            // serP(point(kpar))
            val pub = Secp256k1.publicKeyFromPrivate(parent.privateKey)
            data.write(pub)
        }

        // ser32(idx) — big endian
        val idxBytes = ByteBuffer
            .allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(idx)
            .array()

        data.write(idxBytes)

        val i = CryptoUtils.hmacSha512(parent.chainCode, data.toByteArray())
        val il = i.copyOfRange(0, 32)
        val ir = i.copyOfRange(32, 64)

        val ilInt = BigInteger(1, il)
        val parentKeyInt = BigInteger(1, parent.privateKey)

        // BIP32: invalid key if IL >= n or result == 0
        val childKeyInt = ilInt.add(parentKeyInt).mod(CURVE_N)
        require(childKeyInt != BigInteger.ZERO) {
            "Invalid derived key (zero)"
        }

        return HDKey(
            privateKey = childKeyInt.toByteArray32(),
            chainCode = ir,
            depth = parent.depth + 1,
            index = idx,
            parentFingerprint = Fingerprint.of(parent)
        )
    }
}
