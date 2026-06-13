package com.pokewallet.crypto

object SeedDerivation {

    /**
     * BIP39:
     * seed = PBKDF2(
     *   mnemonic,
     *   salt = "mnemonic" + passphrase,
     *   iterations = 2048,
     *   keylen = 512 bits
     * )
     */
    fun fromMnemonic(
        mnemonicWords: List<String>,
        passphrase: String = ""
    ): ByteArray {

        val mnemonic = mnemonicWords.joinToString(" ")
        val salt = "mnemonic$passphrase"

        return CryptoUtils.pbkdf2Sha512(
            mnemonic = mnemonic,
            salt = salt
        )
    }
}
