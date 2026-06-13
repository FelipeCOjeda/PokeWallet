package com.pokewallet.crypto

data class AuditableUtxo(
    val txidHex: String,
    val vout: Int,
    val value: Long,
    val derivationPath: String
)
