package com.pokewallet.crypto

data class BalanceResult(
    val total: Long,
    val utxos: Map<String, Utxo>
)
