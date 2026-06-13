package com.pokewallet.commands

import com.pokewallet.crypto.*

object UtxosCommand {

    fun run() {

        val ctx = WalletDescriptorContext.fromWalletJson()

        ctx.ensureWalletLoaded()
        ctx.ensureDescriptorsImported()

        val result = WalletUtxoIndex.index(
            rpc = ctx.rpc,
            externalDescriptor = ctx.externalDescriptor,
            internalDescriptor = ctx.internalDescriptor
        )

        if (result.utxos.isEmpty()) {
            println("Nenhum UTXO encontrado.")
            return
        }

        result.utxos.forEach { utxo ->
            println(utxo)
        }
    }
}
