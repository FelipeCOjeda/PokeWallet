package com.pokewallet.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

object ExtendedKeySerializer {

    // -------------------------
    // Version bytes
    // -------------------------

    private val XPUB = byteArrayOf(
        0x04.toByte(), 0x88.toByte(), 0xB2.toByte(), 0x1E.toByte()
    )

    private val ZPUB = byteArrayOf(
        0x04.toByte(), 0xB2.toByte(), 0x47.toByte(), 0x46.toByte()
    )

    private val VPUB = byteArrayOf(
        0x04.toByte(), 0xB2.toByte(), 0x43.toByte(), 0x0C.toByte()
    )

    private val TPUB = byteArrayOf(
        0x04.toByte(), 0x35.toByte(), 0x87.toByte(), 0xCF.toByte()
    )

    // -------------------------
    // Public API
    // -------------------------

    fun serializeXpub(key: HDKey): String =
        serialize(key, XPUB)

    fun serializeZpub(key: HDKey): String =
        serialize(key, ZPUB)

    fun serializeVpub(key: HDKey): String =
        serialize(key, VPUB)

    fun serializeTpub(key: HDKey): String =
        serialize(key, TPUB)

    // -------------------------
    // Core serializer (BIP32)
    // -------------------------

    private fun serialize(
        key: HDKey,
        version: ByteArray
    ): String {

        val pubKey = Secp256k1.publicKeyFromPrivate(key.privateKey)

        val out = ByteArrayOutputStream()

        out.write(version)                              // 4
        out.write(byteArrayOf(key.depth.toByte()))      // 1
        out.write(intToBytes(key.parentFingerprint))    // 4
        out.write(intToBytes(key.index))                // 4
        out.write(key.chainCode)                        // 32
        out.write(pubKey)                               // 33

        val payload = out.toByteArray()
        val checksum = checksum(payload)

        // ✅ retorno correto + variável correta
        return com.pokewallet.Base58.encode(payload + checksum)
    }

    // -------------------------
    // Utils
    // -------------------------

    private fun intToBytes(i: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .putInt(i)
            .array()

    private fun checksum(data: ByteArray): ByteArray {
        val hash = sha256(sha256(data))
        return hash.copyOfRange(0, 4)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
