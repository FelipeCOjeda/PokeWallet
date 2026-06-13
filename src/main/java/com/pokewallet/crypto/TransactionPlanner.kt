package com.pokewallet.crypto

object TransactionPlanner {

    /**
     * Cria um TransactionPlan canônico a partir de:
     * - UTXOs disponíveis
     * - valor alvo
     * - fee rate
     * - tipo de gasto (BIP84 / BIP86)
     * - script de destino
     * - script de change
     */
    fun plan(
        availableUtxos: List<Utxo>,
        targetOutput: TxOut,
        changeOutputBuilder: (Long) -> TxOut,
        feeRateSatPerVbyte: Long,
        spendType: SpendType,
        network: Network = Network.TESTNET
    ): TransactionPlan {

        require(availableUtxos.isNotEmpty()) {
            "Nenhum UTXO disponível"
        }

        // ---------------------------------------------
        // Coin selection
        // ---------------------------------------------

        val (selectedUtxos, changeValue) = CoinSelector.select(
            utxos = availableUtxos,
            targetValue = targetOutput.value,
            feeRateSatPerVbyte = feeRateSatPerVbyte
        )

        // ---------------------------------------------
        // Outputs
        // ---------------------------------------------

        val outputs = mutableListOf<TxOut>()
        outputs += targetOutput

        if (changeValue > 0) {
            outputs += changeOutputBuilder(changeValue)
        }

        // ---------------------------------------------
        // TransactionPlan (imutável)
        // ---------------------------------------------

        return TransactionPlan(
            utxos = selectedUtxos,
            outputs = outputs,
            feeRateSatPerVbyte = feeRateSatPerVbyte,
            spendType = spendType,
            network = network
        )
    }
}
