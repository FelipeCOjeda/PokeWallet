package com.pokewallet.crypto

/**
 * DescriptorBuilder
 *
 * Gera descriptors canônicos BIP84 / BIP86
 * usando fingerprint + XPUB (neutered).
 *
 * NÃO calcula checksum.
 */
object DescriptorBuilder {

    fun bip84External(
        fingerprint: Int,
        xpub: String,
        network: Network
    ): String {
        val fpr = fingerprintToHex(fingerprint)
        val coinType = network.coinType
        return "wpkh([$fpr/84h/${coinType}h/0h]$xpub/0/*)"
    }

    fun bip84Change(
        fingerprint: Int,
        xpub: String,
        network: Network
    ): String {
        val fpr = fingerprintToHex(fingerprint)
        val coinType = network.coinType
        return "wpkh([$fpr/84h/${coinType}h/0h]$xpub/1/*)"
    }

    fun bip86External(
        fingerprint: Int,
        xpub: String,
        network: Network
    ): String {
        val fpr = fingerprintToHex(fingerprint)
        val coinType = network.coinType
        return "tr([$fpr/86h/${coinType}h/0h]$xpub/0/*)"
    }

    fun bip86Change(
        fingerprint: Int,
        xpub: String,
        network: Network
    ): String {
        val fpr = fingerprintToHex(fingerprint)
        val coinType = network.coinType
        return "tr([$fpr/86h/${coinType}h/0h]$xpub/1/*)"
    }

    private fun fingerprintToHex(fp: Int): String =
        fp.toUInt().toString(16).padStart(8, '0')
}
