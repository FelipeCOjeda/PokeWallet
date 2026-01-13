package com.pokewallet.crypto

enum class Network(
    val hrp: String,
    val coinType: Int
) {
    MAINNET("bc", 0),
    TESTNET("tb", 1)
}
