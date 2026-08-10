package com.pokewallet.crypto

import java.nio.ByteBuffer
import kotlin.math.ln

/**
 * Entropia extra por dado físico (d6), motivada pelo bug de RNG da
 * Coldcard (2026): firmware pulava o RNG de hardware e gerava seeds com
 * ~40 bits de entropia em vez de 128. Usuários que combinaram dice rolls
 * com a entropia do dispositivo não foram afetados — essa é a mesma
 * defesa-em-profundidade aqui: mesmo que o SecureRandom do Android seja
 * comprometido algum dia, os dados físicos entram na mistura.
 */
object DiceEntropy {

    const val MIN_ROLLS = 20
    const val RECOMMENDED_ROLLS = 99

    private val LOG2_6 = ln(6.0) / ln(2.0)

    /** Converte uma sequência digitada/colada (ex: "351624...") em lançamentos.
     *  Aceita espaços entre dígitos; retorna null se algum caractere não for 1-6. */
    fun parseRolls(input: String): List<Int>? {
        val cleaned = input.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        if (!cleaned.all { it in '1'..'6' }) return null
        return cleaned.map { it - '0' }
    }

    /** Bits de entropia estimados pra N lançamentos de d6 (log2(6) por lançamento). */
    fun entropyBits(rollCount: Int): Double = rollCount * LOG2_6

    /**
     * Mistura SecureRandom + lançamentos de dado + nanoTime via SHA-256.
     * O resultado nunca é mais fraco que o SecureRandom sozinho (é um dos
     * três insumos do hash) — os dados físicos são reforço, não substituto.
     */
    fun mixEntropy(diceRolls: List<Int>, byteSize: Int): ByteArray {
        require(byteSize == 16 || byteSize == 32) { "byteSize deve ser 16 ou 32" }
        require(diceRolls.size >= MIN_ROLLS) {
            "Mínimo de $MIN_ROLLS lançamentos (fornecidos: ${diceRolls.size})"
        }
        require(diceRolls.all { it in 1..6 }) { "Cada lançamento deve ser um valor de 1 a 6" }

        val systemEntropy = CryptoUtils.randomEntropy(32)
        val diceBytes = diceRolls.map { it.toByte() }.toByteArray()
        val nanoBytes = ByteBuffer.allocate(8).putLong(System.nanoTime()).array()

        val mixed = CryptoUtils.sha256(systemEntropy + diceBytes + nanoBytes)
        return if (byteSize == 32) mixed else mixed.copyOf(16)
    }
}
