package com.pokewallet.crypto

import com.pokewallet.crypto.SeedDerivation

data class WalletSecrets(
    val mnemonic: List<String>,
    val pokemon: String,
    val seed: ByteArray
)

fun createWallet(
    userEvents: List<Triple<Float, Float, Long>>
): WalletSecrets {

    val collector = EntropyCollector()

    userEvents.forEach { (x, y, t) ->
        collector.addUserEvent(x, y, t)
    }

    val rawEntropy = collector.collect()

    // SHA256 dupla (implementamos abaixo)
    val entropy256 = CryptoUtils.sha256(
        CryptoUtils.sha256(rawEntropy)
    )

    val mnemonic = Bip39.generateMnemonic(entropy256)

    // Pokémon = passphrase secreta
    val pokemon = PokemonPassphrase.choose(entropy256)

    val seed = SeedDerivation.fromMnemonic(
        mnemonicWords = mnemonic,
        passphrase = pokemon
    )

    return WalletSecrets(
        mnemonic = mnemonic,
        pokemon = pokemon,
        seed = seed
    )
}
