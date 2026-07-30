package com.pokewallet.crypto

/**
 * ReceiveAddressService
 *
 * Stateless.
 * Deriva endereço EXPLICITAMENTE a partir de um índice.
 */
object ReceiveAddressService {

    fun addressAt(
        seed: ByteArray,
        spendType: SpendType,
        network: Network,
        index: Int
    ): String {

        val path = when (spendType) {

            SpendType.BIP84 -> intArrayOf(
                84 or 0x80000000.toInt(),
                network.coinType or 0x80000000.toInt(),
                0 or 0x80000000.toInt(),
                0,          // external
                index
            )

            SpendType.BIP86 -> intArrayOf(
                86 or 0x80000000.toInt(),
                network.coinType or 0x80000000.toInt(),
                0 or 0x80000000.toInt(),
                0,          // external
                index
            )
        }

        val key = KeyDerivation.derive(seed, path)

        return when (spendType) {

            SpendType.BIP84 -> {
                val pubKey = Secp256k1.publicKeyFromPrivate(key.privateKey)
                val hash160 = Hashes.hash160(pubKey)
                AddressBuilder.p2wpkh(hash160, network)
            }

            SpendType.BIP86 -> {
                val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(key.privateKey)
                val outputKey = Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
                AddressBuilder.p2tr(outputKey, network)
            }
        }
    }
}
