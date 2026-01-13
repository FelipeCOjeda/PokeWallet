package com.pokewallet.crypto

import java.math.BigInteger
import java.security.MessageDigest

object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(data: ByteArray): String {
        var num = BigInteger(1, data)
        val sb = StringBuilder()

        while (num > BigInteger.ZERO) {
            val divRem = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[divRem[1].toInt()])
            num = divRem[0]
        }

        // leading zeros → '1'
        for (b in data) {
            if (b.toInt() == 0) sb.append('1') else break
        }

        return sb.reverse().toString()
    }

    fun encodeCheck(data: ByteArray): String {
        val checksum = checksum(data)
        return encode(data + checksum)
    }

    private fun checksum(data: ByteArray): ByteArray {
        val hash1 = sha256(data)
        val hash2 = sha256(hash1)
        return hash2.copyOfRange(0, 4)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
