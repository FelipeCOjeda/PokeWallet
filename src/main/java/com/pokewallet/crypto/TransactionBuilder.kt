package com.pokewallet.crypto

object TransactionBuilder {

    fun buildUnsignedTransaction(
        plan: TransactionPlan
    ): UnsignedTransaction {

        val inputs = plan.utxos.map {
            TxIn(
                prevTxId = it.txid,
                prevIndex = it.vout,
                scriptSig = byteArrayOf(),
                sequence = 0xFFFFFFFFL
            )
        }

        return UnsignedTransaction(
            version = 2,
            inputs = inputs,
            outputs = plan.outputs,
            lockTime = 0
        )
    }

    fun buildPsbt(
        unsignedTx: UnsignedTransaction,
        plan: TransactionPlan
    ): Any {
        return when (plan.spendType) {
            SpendType.BIP84 ->
                Psbt(
                    unsignedTx = unsignedTx,
                    inputs = MutableList(unsignedTx.inputs.size) { PsbtInput() },
                    outputs = MutableList(unsignedTx.outputs.size) { PsbtOutput() }
                )

            SpendType.BIP86 ->
                PsbtTaproot(
                    unsignedTx = unsignedTx,
                    inputs = MutableList(unsignedTx.inputs.size) { TaprootPsbtInput() },
                    outputs = MutableList(unsignedTx.outputs.size) { PsbtOutput() }
                )
        }
    }
}
