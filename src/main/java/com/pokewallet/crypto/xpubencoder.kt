package com.pokewallet.crypto

import com.pokewallet.Base58
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object XpubEncoder {

    fun encode(key: HDKey, network: Network): String {

        val version = when (network) {
            Network.MAINNET -> 0x0488B21E
            Network.TESTNET,
            Network.REGTEST -> 0x043587CF
        }

        val out = ByteArrayOutputStream()

        out.write(intToBytes(version))
        out.write(byteArrayOf(key.depth.toByte()))
        out.write(intToBytes(key.parentFingerprint))
        out.write(intToBytes(key.index))
        out.write(key.chainCode)

        val pubKey = Secp256k1.publicKeyFromPrivate(key.privateKey)
        out.write(pubKey)

        val payload = out.toByteArray()
        val checksum = checksum(payload)

        return Base58.encode(payload + checksum)
    }

    private fun intToBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun checksum(data: ByteArray): ByteArray {
        val h1 = sha256(data)
        val h2 = sha256(h1)
        return h2.copyOfRange(0, 4)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
