package com.pokewallet.crypto

/**
 * Deriva chaves e monta/assina a transação final no caminho de assinatura
 * LOCAL (carteira com seed neste aparelho) — extraído de
 * WalletViewModel.buildSignedTx() (achado ALTO 11/12 da auditoria: God
 * Object sem teste cobrindo o código que efetivamente move fundos). Puro:
 * seed + UTXOs já resolvidos entram, tx assinada sai — nenhuma dependência
 * de Android, WalletStorage ou rede.
 */
object TxAssembler {

    data class SpendableInput(
        val txidLE: ByteArray,
        val vout: Int,
        val valueSats: Long,
        val scriptPubKey: ByteArray,
        val privateKey: ByteArray,
        val pubKey: ByteArray
    )

    /** privKey, pubKey, scriptPubKey pro endereço (chain,index) — mesmo
     *  formato de script que [deriveSpendableInputs]/troco usam, conforme
     *  o tipo de carteira (SegWit v0 ou Taproot). */
    fun deriveKeyAndScript(seed: ByteArray, network: Network, spendType: SpendType, chain: Int, index: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val hdKey = when (spendType) {
            SpendType.BIP84 -> KeyDerivation.bip84(seed, coin = network.coinType, account = 0, change = chain, address = index)
            SpendType.BIP86 -> KeyDerivation.bip86(seed, coin = network.coinType, account = 0, change = chain, address = index)
        }
        val pk  = hdKey.privateKey
        val pub = Secp256k1.publicKeyFromPrivate(pk)
        val script = when (spendType) {
            SpendType.BIP84 -> byteArrayOf(0x00, 0x14) + Hashes.hash160(pub)
            SpendType.BIP86 -> {
                val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(pk)
                byteArrayOf(0x51, 0x20) + Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
            }
        }
        return Triple(pk, pub, script)
    }

    /** Deriva a chave de gasto de cada UTXO escolhido — uma vez por
     *  endereço (chain,index) mesmo que ele tenha múltiplos UTXOs, evitando
     *  derivação repetida. */
    fun deriveSpendableInputs(
        chosen: List<SpendResolver.Candidate>,
        seed: ByteArray,
        network: Network,
        spendType: SpendType
    ): List<SpendableInput> {
        val keyCache = mutableMapOf<Pair<Int, Int>, Triple<ByteArray, ByteArray, ByteArray>>()
        return chosen.map { c ->
            val (privKey, pubKey, spk) = keyCache.getOrPut(c.chain to c.index) {
                deriveKeyAndScript(seed, network, spendType, c.chain, c.index)
            }
            SpendableInput(
                txidLE       = c.utxo.txid.hexToBytes().reversedArray(),
                vout         = c.utxo.vout,
                valueSats    = c.utxo.valueSats,
                scriptPubKey = spk,
                privateKey   = privKey,
                pubKey       = pubKey
            )
        }
    }

    /**
     * Calcula o scriptPubKey REAL de um output Silent Payments (BIP-352)
     * pros UTXOs JÁ ESCOLHIDOS de um envio — só quem tem as chaves privadas
     * de TODOS os inputs sendo gastos consegue calcular isso (precisa do
     * ECDH remetente, ``a·B_scan``), por isso só existe no caminho de
     * assinatura LOCAL (carteira com seed neste aparelho); watch-only
     * air-gapped fica pra fase futura (ver plano, Fase 4).
     *
     * Deriva a chave de cada input do jeito que [com.pokewallet.crypto.Bip352]
     * exige — chave JÁ TWEAKED pra Taproot (BIP86), chave HD crua pra
     * SegWit v0 (BIP84), ver nota de armadilha em [Bip352.SenderInput] — e
     * soma UMA vez POR INPUT, sem deduplicar por endereço repetido (BIP-352
     * soma ``a = a1 + a2 + ... + an``, um termo por input elegível, conferido
     * direto no texto da spec: não há deduplicação de chave/endereço). Zera
     * toda chave privada temporária usada só pra este cálculo antes de
     * retornar — a assinatura de verdade deriva de novo em
     * [deriveSpendableInputs], os dois caminhos não compartilham array de
     * chave por design.
     */
    fun resolveSilentPaymentDestination(
        chosen: List<SpendResolver.Candidate>,
        destination: String,
        seed: ByteArray,
        network: Network,
        spendType: SpendType
    ): ByteArray {
        val decoded = SilentPaymentAddress.decode(destination, network)
        val rawKeyCache = mutableMapOf<Pair<Int, Int>, ByteArray>()
        val tweakedKeys = mutableListOf<ByteArray>()
        try {
            val senderInputs = chosen.map { c ->
                val rawKey = rawKeyCache.getOrPut(c.chain to c.index) {
                    deriveKeyAndScript(seed, network, spendType, c.chain, c.index).first
                }
                val effectiveKey = when (spendType) {
                    SpendType.BIP84 -> rawKey
                    SpendType.BIP86 -> Secp256k1.taprootTweakPrivateKey(rawKey).also { tweakedKeys += it }
                }
                Bip352.SenderInput(effectiveKey, isTaproot = spendType == SpendType.BIP86)
            }
            val outpoints = chosen.map { c ->
                Bip352.outpoint(c.utxo.txid.hexToBytes().reversedArray(), c.utxo.vout)
            }
            return Bip352.deriveSenderOutputScript(
                inputs      = senderInputs,
                outpoints   = outpoints,
                scanPubKey  = decoded.scanPubKey,
                spendPubKey = decoded.spendPubKey
            )
        } finally {
            rawKeyCache.values.forEach { it.fill(0) }
            tweakedKeys.forEach { it.fill(0) }
        }
    }

    /**
     * Monta e assina a tx final a partir dos inputs já derivados
     * ([deriveSpendableInputs]) e dos outputs (destino [+troco]) — mesma
     * lógica pros dois tipos de endereço suportados (BIP84 SegWit v0 via
     * PSBT normal + partial signature; BIP86 Taproot via PsbtTaproot +
     * sighash dedicado). Zera as privkeys de [spendable] antes de retornar.
     * Retorna (rawTxBytes, txid).
     */
    fun signAndFinalize(
        spendable: List<SpendableInput>,
        outputs: List<TxOut>,
        spendType: SpendType
    ): Pair<ByteArray, String> {
        val txInputs = spendable.map { s ->
            TxIn(prevTxId = s.txidLE, prevIndex = s.vout, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)
        }
        val unsignedTx = UnsignedTransaction(version = 2, inputs = txInputs, outputs = outputs, lockTime = 0L)

        return when (spendType) {

            SpendType.BIP84 -> {
                val psbt = Psbt(
                    unsignedTx = unsignedTx,
                    inputs     = MutableList(txInputs.size) { PsbtInput() },
                    outputs    = MutableList(outputs.size) { PsbtOutput() }
                )

                spendable.forEachIndexed { i, s ->
                    val sig = SegwitSigner.sign(
                        unsignedTx   = unsignedTx,
                        inputIndex   = i,
                        utxoValue    = s.valueSats,
                        scriptPubKey = s.scriptPubKey,
                        privateKey   = s.privateKey
                    )
                    psbt.inputs[i].witnessUtxo = TxOut(s.valueSats, s.scriptPubKey)
                    psbt.inputs[i].partialSignatures[s.pubKey.toHex()] = sig
                }
                spendable.forEach { it.privateKey.fill(0) }

                Pair(psbt.finalize(), psbt.txid())
            }

            SpendType.BIP86 -> {
                val psbt = PsbtTaproot(
                    unsignedTx = unsignedTx,
                    inputs     = MutableList(txInputs.size) { TaprootPsbtInput() },
                    outputs    = MutableList(outputs.size) { PsbtOutput() }
                )

                val utxoTxOuts = spendable.map { TxOut(it.valueSats, it.scriptPubKey) }

                spendable.forEachIndexed { i, s ->
                    psbt.inputs[i].witnessUtxo = TxOut(s.valueSats, s.scriptPubKey)

                    val sighash = TaprootSighashCalculator.calculate(
                        tx         = unsignedTx,
                        inputIndex = i,
                        utxos      = utxoTxOuts
                    )
                    val tweakedPrivKey = Secp256k1.taprootTweakPrivateKey(s.privateKey)
                    try {
                        psbt.inputs[i].tapKeySig = SchnorrSigner.sign(
                            msg32     = sighash,
                            privKey32 = tweakedPrivKey
                        )
                    } finally {
                        tweakedPrivKey.fill(0)
                    }
                }
                spendable.forEach { it.privateKey.fill(0) }

                Pair(psbt.finalize(), unsignedTx.txid())
            }
        }
    }
}
