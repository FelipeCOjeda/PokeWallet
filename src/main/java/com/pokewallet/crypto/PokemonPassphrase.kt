package com.pokewallet.crypto

object PokemonPassphrase {

    fun choose(entropy256: ByteArray): String {
        val salted = entropy256 + "pokemon".toByteArray()
        val hash = CryptoUtils.sha256(salted)

        val index =
            (((hash[0].toInt() and 0xFF) shl 8) or
             (hash[1].toInt() and 0xFF)) % PokemonGen1.LIST.size

        return fromPokemon(index)
    }

    private fun fromPokemon(index: Int): String =
        "pokemon:${index + 1}:${PokemonGen1.LIST[index]}"
}

