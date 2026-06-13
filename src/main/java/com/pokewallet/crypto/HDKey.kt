package com.pokewallet.crypto

data class HDKey(
    val privateKey: ByteArray,
    val chainCode: ByteArray,
    val depth: Int,
    val index: Int,
    val parentFingerprint: Int
)
