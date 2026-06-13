package com.pokewallet.crypto

object DescriptorFactory {

    fun bip84(
        fingerprint: Int,
        zpub: String,
        network: Network = Network.MAINNET,
        account: Int = 0,
        change: Boolean = false
    ): String {
        val path = "84h/${network.coinType}h/${account}h"
        val branch = if (change) 1 else 0
        return "wpkh([${fpHex(fingerprint)}/$path]$zpub/$branch/*)"
    }

    fun bip86(
        fingerprint: Int,
        xpub: String,
        network: Network = Network.MAINNET,
        account: Int = 0,
        change: Boolean = false
    ): String {
        val path = "86h/${network.coinType}h/${account}h"
        val branch = if (change) 1 else 0
        return "tr([${fpHex(fingerprint)}/$path]$xpub/$branch/*)"
    }

    private fun fpHex(fingerprint: Int): String {
        return "%08x".format(fingerprint)
    }
}
