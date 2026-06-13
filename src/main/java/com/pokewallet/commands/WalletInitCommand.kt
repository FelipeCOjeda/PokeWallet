package com.pokewallet.commands

import com.pokewallet.crypto.WalletInit

object WalletInitCommand : Command {
    override fun run(args: List<String>) {
        WalletInit.run()
    }
}
