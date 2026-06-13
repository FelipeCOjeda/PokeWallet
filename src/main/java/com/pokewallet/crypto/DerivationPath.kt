package com.pokewallet.crypto

/**
 * Representa um caminho BIP32 de forma tipada e segura.
 * Exemplo: m/84'/0'/0'/0/15
 */
class DerivationPath private constructor(
    private val path: IntArray
) {

    fun deriveFrom(seed: ByteArray): HDKey {
        return KeyDerivation.derive(seed, path)
    }

    fun toIntArray(): IntArray = path.copyOf()

    override fun toString(): String =
        "m/" + path.joinToString("/") {
            val hardened = it and HARDENED_FLAG != 0
            val idx = it and 0x7fffffff
            if (hardened) "$idx'" else "$idx"
        }

    companion object {

        private const val HARDENED_FLAG = 0x80000000.toInt()

        fun hardened(index: Int): Int =
            index or HARDENED_FLAG

        fun of(vararg elements: Int): DerivationPath =
            DerivationPath(elements) // ← AQUI estava o erro

        /** m/84'/coin'/account'/change/address */
        fun bip84(
            coin: Int = 0,
            account: Int = 0,
            change: Int = 0,
            address: Int = 0
        ): DerivationPath =
            of(
                hardened(84),
                hardened(coin),
                hardened(account),
                change,
                address
            )

        /** m/86'/coin'/account'/change/address */
        fun bip86(
            coin: Int = 0,
            account: Int = 0,
            change: Int = 0,
            address: Int = 0
        ): DerivationPath =
            of(
                hardened(86),
                hardened(coin),
                hardened(account),
                change,
                address
            )
    }
}

