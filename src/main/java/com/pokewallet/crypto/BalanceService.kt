package com.pokewallet.crypto

object BalanceService {

    data class Balance(
        val total: Long,
        val utxos: List<WalletUtxoIndex.IndexedUtxo>
    )

    fun getBalance(ctx: WalletDescriptorContext): Balance {

        ctx.ensureWalletLoaded()
        ctx.ensureDescriptorsImported()

        val result = WalletUtxoIndex.index(
            rpc = ctx.rpc,
            externalDescriptor = ctx.externalDescriptor,
            internalDescriptor = ctx.internalDescriptor
        )

        return Balance(
            total = result.totalSats,
            utxos = result.utxos
        )
    }
}
