package com.pokewallet.lightning

import breez_sdk_spark.Network as SparkNetwork
import com.pokewallet.crypto.Network as WalletNetwork

/**
 * A Breez SDK - Spark só suporta MAINNET e REGTEST (protocolo Spark ainda
 * não tem rede de testes pública equivalente à testnet/signet do Bitcoin
 * comum) — TESTNET da carteira fica de fora da feature Lightning, não é
 * omissão. Conferido direto no enum `breez_sdk_spark.Network` do binding
 * (só duas entradas) em 2026-09-08.
 */
fun WalletNetwork.toSparkNetworkOrNull(): SparkNetwork? = when (this) {
    WalletNetwork.MAINNET -> SparkNetwork.MAINNET
    WalletNetwork.REGTEST -> SparkNetwork.REGTEST
    WalletNetwork.TESTNET -> null
}

val WalletNetwork.supportsLightning: Boolean
    get() = toSparkNetworkOrNull() != null
