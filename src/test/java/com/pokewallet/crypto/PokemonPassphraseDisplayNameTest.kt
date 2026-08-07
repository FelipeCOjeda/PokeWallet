package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mesmo regex usado em WalletViewModel.resolveDisplayName() (duplicado aqui
 * de propósito — é `private` lá, e o contrato que queremos travar é
 * justamente "PokemonPassphrase.choose() sempre produz algo que esse regex
 * consegue casar", não a regex em si).
 */
private val POKEMON_PASSPHRASE_REGEX = Regex("^pokemon:\\d+:(.+)$")

class PokemonPassphraseDisplayNameTest {

    @Test
    fun `choose() output sempre bate com o regex de display name`() {
        repeat(500) {
            val passphrase = PokemonPassphrase.choose()
            val match = POKEMON_PASSPHRASE_REGEX.find(passphrase)
            assertTrue("passphrase \"$passphrase\" não bateu com o regex", match != null)
            val name = match!!.groupValues[1].trim()
            assertTrue("nome extraído veio em branco pra \"$passphrase\"", name.isNotBlank())
            assertTrue("nome extraído \"$name\" não está na lista Gen1", PokemonGen1.LIST.contains(name))
        }
    }

    @Test
    fun `formato exato de um caso conhecido`() {
        // index 0 -> dex 1 -> Bulbasaur
        val passphrase = "pokemon:1:Bulbasaur"
        val match = POKEMON_PASSPHRASE_REGEX.find(passphrase)
        assertEquals("Bulbasaur", match?.groupValues?.get(1))
    }
}
