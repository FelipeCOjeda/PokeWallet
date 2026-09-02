package com.pokewallet.crypto

/**
 * Deriva as chaves de scan e de gasto do Silent Payments (BIP-352) a
 * partir da seed da wallet.
 *
 * Path confirmado direto do texto do BIP-352 (`bip-0352.mediawiki`,
 * seção "Key Derivation"):
 *   scan_private_key:  m / 352' / coin_type' / account' / 1' / 0
 *   spend_private_key: m / 352' / coin_type' / account' / 0' / 0
 *
 * Reparar que o ÚLTIMO nível (0) NÃO é hardened — só os 4 primeiros são
 * ("purpose'/coin_type'/account'/{0',1}'"). Isso é intencional (não um
 * BIP44-style "todos hardened"): o nó pai (.../1' pro scan, .../0' pro
 * spend) já só é alcançável via derivação hardened, então derivar o
 * último nível sem hardening a partir DESSE pai específico não expõe a
 * seed mestra nem a chave de gasto a partir da chave de scan exportada.
 */
object Bip352KeyDerivation {

    private const val PURPOSE = 352

    fun scanKey(seed: ByteArray, network: Network, account: Int = 0): HDKey =
        KeyDerivation.derive(
            seed,
            intArrayOf(
                KeyDerivation.hardened(PURPOSE),
                KeyDerivation.hardened(network.coinType),
                KeyDerivation.hardened(account),
                KeyDerivation.hardened(1),
                0
            )
        )

    fun spendKey(seed: ByteArray, network: Network, account: Int = 0): HDKey =
        KeyDerivation.derive(
            seed,
            intArrayOf(
                KeyDerivation.hardened(PURPOSE),
                KeyDerivation.hardened(network.coinType),
                KeyDerivation.hardened(account),
                KeyDerivation.hardened(0),
                0
            )
        )
}
