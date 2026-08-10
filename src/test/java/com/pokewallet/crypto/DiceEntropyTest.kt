package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiceEntropyTest {

    private fun rolls(count: Int, seed: Int = 1): List<Int> =
        (0 until count).map { ((it * 7 + seed) % 6) + 1 }

    // -------- parseRolls --------

    @Test
    fun `parseRolls aceita sequencia valida de digitos 1 a 6`() {
        assertEquals(listOf(3, 5, 1, 6, 2, 4), DiceEntropy.parseRolls("351624"))
    }

    @Test
    fun `parseRolls ignora espacos entre digitos`() {
        assertEquals(listOf(1, 2, 3), DiceEntropy.parseRolls(" 1 2 3 "))
    }

    @Test
    fun `parseRolls rejeita string vazia`() {
        assertNull(DiceEntropy.parseRolls(""))
        assertNull(DiceEntropy.parseRolls("   "))
    }

    @Test
    fun `parseRolls rejeita digitos fora de 1 a 6`() {
        assertNull(DiceEntropy.parseRolls("12309"))
        assertNull(DiceEntropy.parseRolls("777"))
    }

    @Test
    fun `parseRolls rejeita caracteres nao numericos`() {
        assertNull(DiceEntropy.parseRolls("12a456"))
    }

    // -------- entropyBits --------

    @Test
    fun `entropyBits cresce linearmente com log2 de 6 por lancamento`() {
        assertEquals(0.0, DiceEntropy.entropyBits(0), 0.001)
        val bits20 = DiceEntropy.entropyBits(20)
        val bits99 = DiceEntropy.entropyBits(99)
        assertTrue("20 lançamentos deveriam dar ~51.7 bits, deu $bits20", bits20 in 51.0..52.5)
        assertTrue("99 lançamentos deveriam dar ~256 bits, deu $bits99", bits99 in 255.0..257.0)
    }

    // -------- mixEntropy --------

    @Test(expected = IllegalArgumentException::class)
    fun `mixEntropy rejeita tamanho invalido`() {
        DiceEntropy.mixEntropy(rolls(MIN_ROLLS_FOR_TEST), byteSize = 20)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mixEntropy rejeita menos lancamentos que o minimo`() {
        DiceEntropy.mixEntropy(rolls(DiceEntropy.MIN_ROLLS - 1), byteSize = 16)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mixEntropy rejeita valor de lancamento fora de 1 a 6`() {
        DiceEntropy.mixEntropy(listOf(1, 2, 3, 7) + rolls(DiceEntropy.MIN_ROLLS), byteSize = 16)
    }

    @Test
    fun `mixEntropy aceita exatamente o minimo`() {
        val entropy = DiceEntropy.mixEntropy(rolls(DiceEntropy.MIN_ROLLS), byteSize = 16)
        assertEquals(16, entropy.size)
    }

    @Test
    fun `mixEntropy produz tamanho correto para 12 e 24 palavras`() {
        assertEquals(16, DiceEntropy.mixEntropy(rolls(DiceEntropy.MIN_ROLLS), byteSize = 16).size)
        assertEquals(32, DiceEntropy.mixEntropy(rolls(DiceEntropy.RECOMMENDED_ROLLS), byteSize = 32).size)
    }

    @Test
    fun `mixEntropy nao repete a mesma saida em chamadas sucessivas (componente SecureRandom e nanoTime vivos)`() {
        val same = rolls(DiceEntropy.RECOMMENDED_ROLLS)
        val a = DiceEntropy.mixEntropy(same, byteSize = 32)
        val b = DiceEntropy.mixEntropy(same, byteSize = 32)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `mixEntropy varia com sequencia de dados diferente`() {
        val a = DiceEntropy.mixEntropy(rolls(DiceEntropy.RECOMMENDED_ROLLS, seed = 1), byteSize = 32)
        val b = DiceEntropy.mixEntropy(rolls(DiceEntropy.RECOMMENDED_ROLLS, seed = 2), byteSize = 32)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `mixEntropy integra com Bip39 e gera mnemonic valido`() {
        val entropy24 = DiceEntropy.mixEntropy(rolls(DiceEntropy.RECOMMENDED_ROLLS), byteSize = 32)
        val mnemonic24 = Bip39.generateMnemonic(entropy24)
        assertEquals(24, mnemonic24.size)

        val entropy12 = DiceEntropy.mixEntropy(rolls(DiceEntropy.MIN_ROLLS), byteSize = 16)
        val mnemonic12 = Bip39.generateMnemonic(entropy12)
        assertEquals(12, mnemonic12.size)
    }

    private companion object {
        const val MIN_ROLLS_FOR_TEST = 20
    }
}
