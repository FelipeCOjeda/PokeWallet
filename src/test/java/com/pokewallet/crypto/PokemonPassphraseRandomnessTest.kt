package com.pokewallet.crypto

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Investigando relato do Felipe: "parece que as passphrases de Pokémon
 * estão sendo escolhidas em sequencial, sendo que devem ser sorteadas".
 * PokemonPassphrase.choose() usa CryptoUtils.randomEntropy(2) (SecureRandom
 * compartilhado) — testa aqui se a sequência de índices sorteados por
 * chamadas consecutivas é realmente aleatória (não segue um padrão
 * incremental óbvio tipo 1,2,3,4... ou sempre o mesmo valor).
 */
class PokemonPassphraseRandomnessTest {

    private fun extractDex(passphrase: String): Int =
        Regex("^pokemon:(\\d+):").find(passphrase)!!.groupValues[1].toInt()

    @Test
    fun `chamadas consecutivas NAO formam sequencia incremental`() {
        val dexNumbers = (1..30).map { extractDex(PokemonPassphrase.choose()) }
        println("Dex sorteados: $dexNumbers")

        val isStrictlyIncrementing = dexNumbers.zipWithNext().all { (a, b) -> b == a + 1 }
        assertTrue("Sequência parece INCREMENTAL (bug real): $dexNumbers", !isStrictlyIncrementing)

        val isConstant = dexNumbers.toSet().size == 1
        assertTrue("Sequência é sempre o MESMO valor (bug real): $dexNumbers", !isConstant)
    }

    @Test
    fun `distribuicao razoavel em 1000 sorteios (nao concentrada em poucos indices)`() {
        val dexNumbers = (1..1000).map { extractDex(PokemonPassphrase.choose()) }
        val distinctCount = dexNumbers.toSet().size
        println("Valores distintos em 1000 sorteios: $distinctCount (de 151 possíveis)")
        // Com sorteio uniforme de verdade, 1000 tentativas em 151 índices
        // deveria cobrir a MAIORIA dos índices (esperado ~145+ distintos,
        // problema de "coupon collector"). Threshold frouxo de propósito
        // (>100) só pra pegar bug grosseiro tipo "sempre os mesmos 2-3
        // valores" ou "sempre o mesmo".
        assertTrue("Distribuição suspeita — só $distinctCount valores distintos em 1000 sorteios", distinctCount > 100)
    }
}
