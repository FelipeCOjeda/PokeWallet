package com.pokewallet.crypto

/**
 * Ponto canônico de carregamento da wallet.
 *
 * Garante:
 * - wallet.json válido
 * - schema correto
 * - wallet existente no Bitcoin Core
 */
object WalletContext {

    fun loadValidated(): WalletData {

        // 1) Carrega do disco (schema + defaults)
        val wallet = WalletStorage.load()

        // 2) Valida contra o Bitcoin Core
        WalletCoreValidator.assertWalletExists(wallet.walletName)

        return wallet
    }
}
