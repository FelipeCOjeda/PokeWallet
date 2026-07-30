package com.pokewallet.crypto

object PokemonPassphrase {

    /**
     * Sorteia um Pokémon com entropia PRÓPRIA (SecureRandom independente)
     * — NÃO deriva mais da entropia do mnemonic.
     *
     * Antes, esta função recebia a MESMA entropia usada pra gerar o
     * mnemonic e calculava SHA256(entropia + "pokemon") — ou seja, a
     * "passphrase" era uma função pública e determinística do próprio
     * mnemonic. Quem obtivesse as 12/24 palavras reconstruía a
     * passphrase exata em milissegundos, sem precisar de mais nada: a
     * suposta camada extra de proteção (a 25ª palavra do BIP39 deveria
     * ser um segredo INDEPENDENTE) não existia de fato.
     *
     * Passphrases já geradas por wallets antigas continuam funcionando
     * normalmente pra restaurar — o valor persistido em wallet.json é a
     * string final "pokemon:N:Nome", nunca recalculado a partir do
     * mnemonic. Só a escolha do Pokémon em wallets NOVAS muda.
     */
    fun choose(): String {
        val random = CryptoUtils.randomEntropy(2)
        val index =
            (((random[0].toInt() and 0xFF) shl 8) or
             (random[1].toInt() and 0xFF)) % PokemonGen1.LIST.size

        return fromPokemon(index)
    }

    private fun fromPokemon(index: Int): String =
        "pokemon:${index + 1}:${PokemonGen1.LIST[index]}"
}

