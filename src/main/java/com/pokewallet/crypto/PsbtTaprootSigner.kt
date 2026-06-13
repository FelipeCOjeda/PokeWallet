package com.pokewallet.crypto

object PsbtTaprootSigner {

    /**
     * Assina input Taproot key-path (BIP86)
     */
    fun signInput(
        psbt: Psbt,
        inputIndex: Int,
        privateKey: ByteArray
    ) {

        val input = psbt.inputs[inputIndex]
        val tx = psbt.unsignedTx

        require(input.witnessUtxo != null) {
            "Taproot requer witnessUtxo"
        }

        // BIP341: todos os outputs anteriores (prevouts)
        val prevOutputs: List<TxOut> = psbt.inputs.map {
            it.witnessUtxo ?: error("Missing witnessUtxo")
        }

        val sighash = SighashCalculator.taprootKeyPathSighash(
            tx = tx,
            inputIndex = inputIndex,
            prevOutputs = prevOutputs,
            sighashType = SighashCalculator.SIGHASH_ALL
        )

        val schnorrSig = Secp256k1.signSchnorr(
            privateKey = privateKey,
            messageHash = sighash
        )

        // Witness Taproot = só a assinatura
        input.finalWitness = listOf(schnorrSig)
    }
}
