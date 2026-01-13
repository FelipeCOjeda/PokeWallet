package com.pokewallet.crypto

object AddressFactory {

    fun deriveAddress(
        seed: ByteArray,
        network: Network = Network.MAINNET,
        type: AddressType,
        account: Int = 0,
        change: Int = 0,
        index: Int = 0
    ): String {

        val purpose = when (type) {
            AddressType.BIP84 -> 84
            AddressType.BIP86 -> 86
        }

        val path = intArrayOf(
            hardened(purpose),
            hardened(network.coinType),
            hardened(account),
            change,
            index
        )

        val hdKey = KeyDerivation.derive(seed, path)
        val pubKey = Secp256k1.publicKeyFromPrivate(hdKey.privateKey)

        return when (type) {

            AddressType.BIP84 -> {
                val program = Hashes.hash160(pubKey)
                Bech32.encodeSegWit(
                    hrp = network.hrp,
                    witnessVersion = 0,
                    program = program
                )
            }

            AddressType.BIP86 -> {
                val xOnly = pubKey.copyOfRange(1, 33) // remove 02/03
                Bech32.encodeSegWit(
                    hrp = network.hrp,
                    witnessVersion = 1,
                    program = xOnly
                )
            }
        }
    }

    private fun hardened(i: Int) = i or 0x80000000.toInt()
}
