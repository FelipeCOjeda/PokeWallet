package com.pokewallet.crypto

fun Int.toFingerprintHex(): String {
    return String.format("%08x", this)
}
