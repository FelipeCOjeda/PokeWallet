package com.pokewallet.crypto

fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
