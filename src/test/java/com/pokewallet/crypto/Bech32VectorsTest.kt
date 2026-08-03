package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vetores de teste OFICIAIS de BIP173 (witness v0) e BIP350 (witness v1 /
 * bech32m), baixados direto do GitHub (bitcoin/bips) — testam
 * Bech32.encodeSegWit isoladamente, na camada abaixo de AddressBuilder.
 *
 * Nota: este arquivo existia antes num diretório (src/tests/kotlin) que
 * não é reconhecido pelos source sets do Gradle — nunca foi de fato
 * compilado nem executado, e usava uma API (encodeBech32m) que não existe
 * no Bech32.kt atual, além de um endereço BIP84 com checksum errado.
 * Movido para src/test/java (source set real) e reescrito contra os
 * vetores oficiais para passar a validar de verdade.
 */
class Bech32VectorsTest {

    @Test
    fun encodesWitnessV0Bip173Vector() {
        val program = "751e76e8199196d454941c45d1b3a323f1433bd6".hexToBytes()
        val addr = Bech32.encodeSegWit(hrp = "bc", witnessVersion = 0, program = program)
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", addr)
    }

    @Test
    fun encodesWitnessV1Bip350Vector() {
        val program = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".hexToBytes()
        val addr = Bech32.encodeSegWit(hrp = "bc", witnessVersion = 1, program = program)
        assertEquals("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", addr)
    }
}
