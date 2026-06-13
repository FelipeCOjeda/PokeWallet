package com.pokewallet.crypto

import java.math.BigInteger

fun BigInteger.toByteArray32(): ByteArray {
    val bytes = this.toByteArray()
    return when {
        bytes.size == 32 -> bytes
        bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
        else -> ByteArray(32 - bytes.size) + bytes
    }
}
