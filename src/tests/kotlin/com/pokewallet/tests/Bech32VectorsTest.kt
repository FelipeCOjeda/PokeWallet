package com.pokewallet.tests

import kotlin.test.Test
import kotlin.test.assertEquals

class Bech32Test {

    @Test
    fun testBip84Vector() {
        val hrp = "bc"
        val program = "751e76e8199196d454941c45d1b3a323f1433bd6"
        val data = byteArrayOf(0x00) + program.chunked(2).map { it.toInt(16).toByte() }
        val addr = Bech32.encode(hrp, Bech32.convertBits(data.toByteArray(), 8, 5))
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080",
            addr
        )
    }

    @Test
    fun testBip86Vector() {
        val hrp = "bc"
        val xOnly = ByteArray(32) { 1 }
        val data = byteArrayOf(0x01) + xOnly
        val addr = Bech32.encodeBech32m(hrp, Bech32.convertBits(data, 8, 5))
        assertEquals(
            "bc1pqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqk09r4",
            addr
        )
    }
}
