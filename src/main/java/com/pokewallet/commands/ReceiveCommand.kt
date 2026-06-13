package com.pokewallet.commands

import com.pokewallet.crypto.*

object ReceiveCommand : Command {

    override fun run(args: List<String>) {

        // -----------------------------
        // Load wallet (fonte da verdade)
        // -----------------------------
        val wallet = WalletContext.loadValidated()

        // -----------------------------
        // Seed
        // -----------------------------
        val seed = SeedDerivation.fromMnemonic(
            wallet.mnemonic,
            wallet.passphrase
        )

        // -----------------------------
        // Índice persistido
        // -----------------------------
        val index = wallet.nextExternalIndex

        // -----------------------------
        // Deriva endereço
        // -----------------------------
        val address = ReceiveAddressService.addressAt(
            seed = seed,
            spendType = wallet.spendType,
            network = wallet.network,
            index = index
        )

        // -----------------------------
        // Incrementa e salva
        // -----------------------------
        wallet.nextExternalIndex = index + 1
        WalletStorage.save(wallet)

        // -----------------------------
        // Output
        // -----------------------------
        println("📥 Receive address:")
        println(address)
        println("🔢 Derivation index: $index")
    }
}
