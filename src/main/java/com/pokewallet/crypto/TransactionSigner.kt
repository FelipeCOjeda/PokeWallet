package com.pokewallet.crypto

/**
 * TransactionSigner
 *
 * Responsável por:
 *  - Assinar PSBTs conforme o TransactionPlan
 *  - Preencher campos obrigatórios de assinatura
 *
 * NÃO é responsável por:
 *  - Construção de transação
 *  - Coin selection
 *  - Serialização final (raw tx)
 */
object TransactionSigner {

    /**
     * Assina o PSBT de acordo com o tipo de gasto definido no plano.
     *
     * Após este método:
     *  - PSBT BIP84 está pronto para finalize()
     *  - PSBT BIP86 está pronto para finalize()
     */
    fun sign(
        psbt: Any,
        plan: TransactionPlan,
        privateKey: ByteArray,
        publicKey: ByteArray
    ) {
        require(plan.utxos.size > 0) {
            "TransactionPlan sem UTXOs"
        }

        when (psbt) {

            // =================================================
            // BIP84 — SegWit v0 (ECDSA / BIP143)
            // =================================================
            is Psbt -> {

                plan.utxos.forEachIndexed { index, utxo ->

                    // witnessUtxo é obrigatório em SegWit v0
                    psbt.inputs[index].witnessUtxo =
                        TxOut(
                            value = utxo.value,
                            scriptPubKey = utxo.scriptPubKey
                        )

                    val sig = SegwitSigner.sign(
                        unsignedTx = psbt.unsignedTx,
                        inputIndex = index,
                        utxoValue = utxo.value,
                        scriptPubKey = utxo.scriptPubKey,
                        privateKey = privateKey
                    )

                    psbt.inputs[index]
                        .partialSignatures[publicKey] = sig
                }
            }

            // =================================================
            // BIP86 — Taproot key-path (Schnorr / BIP341)
            // =================================================
            is PsbtTaproot -> {

                val utxos = plan.utxos.map {
                    TxOut(it.value, it.scriptPubKey)
                }

                plan.utxos.forEachIndexed { index, utxo ->

                    psbt.inputs[index].witnessUtxo =
                        TxOut(
                            value = utxo.value,
                            scriptPubKey = utxo.scriptPubKey
                        )

                    val sighash = TaprootSighashCalculator.calculate(
                        tx = psbt.unsignedTx,
                        inputIndex = index,
                        utxos = utxos
                    )

                    val sig = SchnorrSigner.sign(
                        msg32 = sighash,
                        privKey32 = privateKey
                    )

                    psbt.inputs[index].tapKeySig = sig
                }
            }

            else -> error("Tipo de PSBT não suportado: ${psbt::class.simpleName}")
        }
    }
}
