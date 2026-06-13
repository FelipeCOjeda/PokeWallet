package com.pokewallet

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

    fun decode(input: String): ByteArray {
        var num = BigInteger.ZERO
        for (ch in input) {
            val digit = ALPHABET.indexOf(ch)
            require(digit >= 0) { "Caractere inválido no Base58: $ch" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }

        val bytes = num.toByteArray().let {
            if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }

        val leadingZeros = input.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + bytes
    }

    fun decodeCheck(input: String): ByteArray {
        val full = decode(input)
        require(full.size >= 4) { "Base58Check muito curto" }
        val payload = full.copyOfRange(0, full.size - 4)
        val checksum = full.copyOfRange(full.size - 4, full.size)
        val expected = sha256(sha256(payload)).copyOfRange(0, 4)
        require(checksum.contentEquals(expected)) { "Checksum Base58Check inválido" }
        return payload
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
