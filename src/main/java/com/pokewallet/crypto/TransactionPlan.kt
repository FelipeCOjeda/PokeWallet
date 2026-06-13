package com.pokewallet.crypto

data class TransactionPlan(
    val utxos: List<Utxo>,
    val outputs: List<TxOut>,
    val feeRateSatPerVbyte: Long,
    val spendType: SpendType,
    val network: Network = Network.REGTEST
)

enum class SpendType {
    BIP84, // SegWit v0
    BIP86  // Taproot
}
