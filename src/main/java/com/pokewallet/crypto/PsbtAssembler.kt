package com.pokewallet.crypto

import com.pokewallet.network.XpubAddressDeriver

/**
 * Monta o PSBT NÃO-assinado do lado watch-only do fluxo air-gapped —
 * extraído de WalletViewModel.buildUnsignedPsbtForWatchOnly() (achado ALTO
 * 11/12 da auditoria). Puro: deriva só chaves PÚBLICAS a partir da xpub
 * (XpubAddressDeriver, sem seed/rede) — [changeIndex] entra já reservado
 * pelo chamador (WalletStorage.reserveNextInternalIndex() é I/O em disco,
 * fica no ViewModel) em vez de ser reservado aqui.
 */
object PsbtAssembler {

    data class Result(val psbtBase64: String, val expectedTxid: String)

    fun build(
        chosen: List<SpendResolver.Candidate>,
        destSpk: ByteArray,
        sendAmount: Long,
        changeValue: Long?,
        changeIndex: Int?,
        xpub: String,
        network: Network,
        spendType: SpendType,
        masterFingerprint: ByteArray
    ): Result {
        require((changeValue == null) == (changeIndex == null)) {
            "changeIndex e changeValue têm que ser nulos ou não-nulos juntos"
        }
        val purpose = spendType.bipPurpose()

        fun deriveKeyAndScript(chain: Int, index: Int): Pair<ByteArray, ByteArray> {
            val accountKey = XpubAddressDeriver.decodeXpub(xpub)
            val chainKey   = XpubAddressDeriver.derivePublicChild(accountKey, chain)
            val indexKey   = XpubAddressDeriver.derivePublicChild(chainKey, index)
            val spk = when (spendType) {
                SpendType.BIP84 -> byteArrayOf(0x00, 0x14) + Hashes.hash160(indexKey.pubKey)
                SpendType.BIP86 -> {
                    val xOnly = indexKey.pubKey.copyOfRange(1, 33)
                    byteArrayOf(0x51, 0x20) + Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
                }
            }
            return indexKey.pubKey to spk
        }

        fun derivationPath(chain: Int, index: Int) = listOf(
            KeyDerivation.hardened(purpose), KeyDerivation.hardened(network.coinType), KeyDerivation.hardened(0), chain, index
        )

        val txInputs = mutableListOf<TxIn>()
        // pubKey, scriptPubKey, (chain, index) — um por input, na mesma ordem de `chosen`
        val inputMeta = mutableListOf<Triple<ByteArray, ByteArray, Pair<Int, Int>>>()
        for (c in chosen) {
            txInputs += TxIn(
                prevTxId  = c.utxo.txid.hexToBytes().reversedArray(),
                prevIndex = c.utxo.vout,
                scriptSig = byteArrayOf(),
                sequence  = 0xFFFFFFFFL
            )
            val (pubKey, spk) = deriveKeyAndScript(c.chain, c.index)
            inputMeta += Triple(pubKey, spk, c.chain to c.index)
        }

        var changePubKey: ByteArray? = null
        var changeChainIndex: Pair<Int, Int>? = null
        val txOutputs: List<TxOut> = if (changeValue != null) {
            val (pubKey, spk) = deriveKeyAndScript(1, changeIndex!!)
            changePubKey = pubKey
            changeChainIndex = 1 to changeIndex
            listOf(TxOut(sendAmount, destSpk), TxOut(changeValue, spk))
        } else {
            listOf(TxOut(sendAmount, destSpk))
        }

        val unsignedTx = UnsignedTransaction(version = 2, inputs = txInputs, outputs = txOutputs, lockTime = 0L)
        val expectedTxid = unsignedTx.txid()

        val psbtBase64 = when (spendType) {
            SpendType.BIP84 -> {
                val psbt = Psbt(
                    unsignedTx = unsignedTx,
                    inputs     = MutableList(txInputs.size) { PsbtInput() },
                    outputs    = MutableList(txOutputs.size) { PsbtOutput() }
                )
                inputMeta.forEachIndexed { i, (pubKey, spk, chainIndex) ->
                    psbt.inputs[i].witnessUtxo = TxOut(chosen[i].utxo.valueSats, spk)
                    psbt.inputs[i].bip32Derivations[pubKey.toHex()] =
                        Bip32Derivation(masterFingerprint, derivationPath(chainIndex.first, chainIndex.second))
                }
                if (changePubKey != null && changeChainIndex != null) {
                    psbt.outputs[1].bip32Derivations[changePubKey.toHex()] =
                        Bip32Derivation(masterFingerprint, derivationPath(changeChainIndex.first, changeChainIndex.second))
                }
                psbt.serializeBase64()
            }
            SpendType.BIP86 -> {
                val psbt = PsbtTaproot(
                    unsignedTx = unsignedTx,
                    inputs     = MutableList(txInputs.size) { TaprootPsbtInput() },
                    outputs    = MutableList(txOutputs.size) { PsbtOutput() }
                )
                inputMeta.forEachIndexed { i, (pubKey, spk, chainIndex) ->
                    val xOnly = pubKey.copyOfRange(1, 33)
                    psbt.inputs[i].witnessUtxo = TxOut(chosen[i].utxo.valueSats, spk)
                    psbt.inputs[i].tapInternalKey = xOnly
                    psbt.inputs[i].tapBip32Derivation =
                        TapBip32Derivation(emptyList(), masterFingerprint, derivationPath(chainIndex.first, chainIndex.second))
                }
                if (changePubKey != null && changeChainIndex != null) {
                    val xOnly = changePubKey.copyOfRange(1, 33)
                    psbt.outputs[1].tapBip32Derivations[xOnly.toHex()] =
                        TapBip32Derivation(emptyList(), masterFingerprint, derivationPath(changeChainIndex.first, changeChainIndex.second))
                }
                psbt.serializeBase64()
            }
        }

        return Result(psbtBase64 = psbtBase64, expectedTxid = expectedTxid)
    }
}
