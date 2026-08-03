package com.pokewallet.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serialização binária compartilhada (CompactSize/varint + inteiros
 * little-endian) do formato de transação Bitcoin e PSBT (BIP144/BIP174).
 * Antes duplicada byte-a-byte em Psbt.kt, PsbtTaproot.kt,
 * SighashCalculator.kt, TaprootSighashCalculator.kt e SegwitSigner.kt.
 */
object ByteSerializer {

    fun varInt(value: Long): ByteArray =
        when {
            value < 0xfd -> byteArrayOf(value.toByte())
            value <= 0xffff ->
                byteArrayOf(0xfd.toByte()) + int16LE(value.toInt())
            value <= 0xffffffffL ->
                byteArrayOf(0xfe.toByte()) + int32LE(value.toInt())
            else ->
                byteArrayOf(0xff.toByte()) + int64LE(value)
        }

    fun readVarInt(buf: ByteBuffer): Long {
        val first = buf.get().toInt() and 0xFF
        return when {
            first < 0xfd -> first.toLong()
            first == 0xfd -> buf.short.toLong() and 0xFFFF
            first == 0xfe -> buf.int.toLong() and 0xFFFFFFFFL
            else -> buf.long
        }
    }

    fun int16LE(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort()).array()

    fun int32LE(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value).array()

    fun int64LE(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value).array()
}
