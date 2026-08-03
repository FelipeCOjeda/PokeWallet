package com.pokewallet.crypto

object SeedDerivation {

    /**
     * BIP39: seed = PBKDF2(mnemonic, salt = "mnemonic" + passphrase,
     * iterations = 2048, keylen = 512 bits). Delega pra Bip39.mnemonicToSeed
     * (implementação única, validada contra os vetores oficiais do BIP39
     * em Bip39Test.kt) — mantido como wrapper porque é o nome chamado em
     * toda a base de código (WalletViewModel, WalletInit, WalletRestore,
     * SendCommand, etc.).
     */
    fun fromMnemonic(
        mnemonicWords: List<String>,
        passphrase: String = ""
    ): ByteArray = Bip39.mnemonicToSeed(mnemonicWords, passphrase)
}
