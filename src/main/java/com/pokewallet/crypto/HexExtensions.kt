package com.pokewallet.crypto

fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

/**
 * Decodifica uma string hex pra ByteArray. Não usa java.util.HexFormat
 * de propósito — só existe na plataforma Android a partir da API 34,
 * e o minSdk deste projeto é 26.
 */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string com tamanho ímpar: $length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
