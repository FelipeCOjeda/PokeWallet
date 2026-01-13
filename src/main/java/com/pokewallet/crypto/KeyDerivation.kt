package com.pokewallet.crypto

/**
 * Responsável por derivar chaves HD a partir de uma seed BIP39,
 * seguindo BIP32, BIP84 (SegWit) e BIP86 (Taproot).
 *
 * Esta camada NÃO conhece strings como "m/84'/0'/0'/0/0".
 * Ela opera exclusivamente com índices inteiros já interpretados.
 */
object KeyDerivation {

    private const val HARDENED_FLAG: Int = 0x80000000.toInt()

    /**
     * Marca um índice como hardened (BIP32).
     * Ex: hardened(84) == 84'
     */
    fun hardened(index: Int): Int =
        index or HARDENED_FLAG

    /**
     * Verifica se um índice é hardened.
     */
    fun isHardened(index: Int): Boolean =
        index and HARDENED_FLAG != 0

    /**
     * Remove o bit hardened, retornando apenas o índice base.
     */
    fun unharden(index: Int): Int =
        index and 0x7fffffff

    /**
     * Deriva uma chave HD seguindo um caminho BIP32.
     *
     * Exemplo de path (BIP84):
     * intArrayOf(
     *   hardened(84),
     *   hardened(0),
     *   hardened(0),
     *   0,
     *   15
     * )
     */
    fun derive(
        seed: ByteArray,
        path: IntArray
    ): HDKey {

        var key = Bip32.fromSeed(seed)

        for (rawIndex in path) {
            val hardened = isHardened(rawIndex)
            val index = unharden(rawIndex)

            key = Bip32.deriveChild(
                parent = key,
                index = index,
                hardened = hardened
            )
        }

        return key
    }

    /* =========================
       Helpers de alto nível
       ========================= */

    /**
     * BIP84 – Native SegWit (bc1q)
     * m / 84' / coin' / account' / change / address
     */
    fun bip84(
        seed: ByteArray,
        coin: Int = 0,
        account: Int = 0,
        change: Int = 0,
        address: Int = 0
    ): HDKey =
        derive(
            seed,
            intArrayOf(
                hardened(84),
                hardened(coin),
                hardened(account),
                change,
                address
            )
        )

    /**
     * BIP86 – Taproot (bc1p)
     * m / 86' / coin' / account' / change / address
     */
    fun bip86(
        seed: ByteArray,
        coin: Int = 0,
        account: Int = 0,
        change: Int = 0,
        address: Int = 0
    ): HDKey =
        derive(
            seed,
            intArrayOf(
                hardened(86),
                hardened(coin),
                hardened(account),
                change,
                address
            )
        )
}
