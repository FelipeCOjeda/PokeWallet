package com.pokewallet.crypto

/**
 * Assina o PSBT recebido no lado SIGNER do fluxo air-gapped (Fase C4/C5) —
 * extraído de WalletViewModel (achado ALTO 11/12 da auditoria: God Object
 * sem teste cobrindo o código que assina fundos de verdade). Puro: só
 * seed/wallet/network entram, (rawTxHex, txid) sai — nenhuma dependência de
 * Android, WalletStorage ou rede, por isso testável com um PSBT sintético.
 */
object AirGappedPsbtSigner {

    /** (rawTxHex, txid) do PSBT SegWit v0 assinado com a chave certa por input. */
    fun signSegwit(psbtBase64: String, seed: ByteArray, wallet: WalletData, network: Network): Pair<String, String> {
        val psbt = try {
            Psbt.parseBase64(psbtBase64)
        } catch (e: Exception) {
            error("PSBT inválido ou corrompido: ${e.message}")
        }
        require(psbt.unsignedTx.inputs.isNotEmpty()) { "PSBT sem inputs" }

        psbt.inputs.forEachIndexed { i, input ->
            val utxo = input.witnessUtxo ?: error("Input $i sem witness_utxo — PSBT incompleto")
            val (pubKeyHex, deriv) = input.bip32Derivations.entries.firstOrNull()
                ?: error("Input $i sem informação de derivação — não sei qual chave usar pra assinar")
            // Fingerprint zerado = "desconhecido" (PSBT veio de watch-only importada
            // só por xpub pura, sem o fingerprint mestre real) — nesse caso não dá
            // pra conferir aqui, a checagem de segurança real é a de script mais
            // abaixo (recalcula a partir da chave derivada e confere contra o UTXO).
            if (!deriv.masterFingerprint.all { it == 0.toByte() }) {
                require(deriv.masterFingerprint.toHex() == wallet.fingerprint) {
                    "Esse PSBT foi montado por outra carteira (fingerprint ${deriv.masterFingerprint.toHex()} ≠ ${wallet.fingerprint} desta)."
                }
            }
            require(deriv.path.size == 5) { "Caminho de derivação inesperado no input $i" }
            val chain = deriv.path[3]
            val index = deriv.path[4]

            val hdKey = KeyDerivation.bip84(seed, coin = network.coinType, account = 0, change = chain, address = index)
            try {
                val pubKey = Secp256k1.publicKeyFromPrivate(hdKey.privateKey)
                require(pubKey.toHex() == pubKeyHex) {
                    "Pubkey derivada não bate com a do PSBT no input $i — dado corrompido ou adulterado, assinatura recusada."
                }
                // Recalcula o scriptPubKey a partir da CHAVE DERIVADA LOCALMENTE (não confia
                // cegamente no scriptPubKey que veio no PSBT) — se não bater, o PSBT está
                // pedindo pra assinar algo que não corresponde à chave que ele mesmo declarou.
                val expectedSpk = byteArrayOf(0x00, 0x14) + Hashes.hash160(pubKey)
                require(expectedSpk.contentEquals(utxo.scriptPubKey)) {
                    "scriptPubKey do input $i não bate com o esperado pra essa chave — assinatura recusada."
                }

                val sig = SegwitSigner.sign(
                    unsignedTx   = psbt.unsignedTx,
                    inputIndex   = i,
                    utxoValue    = utxo.value,
                    scriptPubKey = utxo.scriptPubKey,
                    privateKey   = hdKey.privateKey
                )
                psbt.inputs[i].partialSignatures[pubKeyHex] = sig
            } finally {
                hdKey.privateKey.fill(0)
            }
        }

        val rawTxBytes = psbt.finalize()
        return rawTxBytes.joinToString("") { "%02x".format(it) } to psbt.txid()
    }

    /** (rawTxHex, txid) do PSBT Taproot assinado com a chave certa por input. */
    fun signTaproot(psbtBase64: String, seed: ByteArray, wallet: WalletData, network: Network): Pair<String, String> {
        val psbt = try {
            PsbtTaproot.parseBase64(psbtBase64)
        } catch (e: Exception) {
            error("PSBT inválido ou corrompido: ${e.message}")
        }
        require(psbt.unsignedTx.inputs.isNotEmpty()) { "PSBT sem inputs" }

        val allUtxos = psbt.inputs.mapIndexed { i, input ->
            input.witnessUtxo ?: error("Input $i sem witness_utxo — necessário em todos os inputs pro cálculo do sighash Taproot")
        }

        psbt.inputs.forEachIndexed { i, input ->
            val utxo = allUtxos[i]
            val deriv = input.tapBip32Derivation
                ?: error("Input $i sem informação de derivação — não sei qual chave usar pra assinar")
            // Fingerprint zerado = "desconhecido" (PSBT veio de watch-only importada
            // só por xpub pura, sem o fingerprint mestre real) — nesse caso não dá
            // pra conferir aqui, a checagem de segurança real é a de script mais
            // abaixo (recalcula a partir da chave derivada e confere contra o UTXO).
            if (!deriv.masterFingerprint.all { it == 0.toByte() }) {
                require(deriv.masterFingerprint.toHex() == wallet.fingerprint) {
                    "Esse PSBT foi montado por outra carteira (fingerprint ${deriv.masterFingerprint.toHex()} ≠ ${wallet.fingerprint} desta)."
                }
            }
            require(deriv.path.size == 5) { "Caminho de derivação inesperado no input $i" }
            val chain = deriv.path[3]
            val index = deriv.path[4]

            val hdKey = KeyDerivation.bip86(seed, coin = network.coinType, account = 0, change = chain, address = index)
            val tweakedPrivKey = Secp256k1.taprootTweakPrivateKey(hdKey.privateKey)
            try {
                val xOnly = Secp256k1.xOnlyPublicKeyFromPrivate(hdKey.privateKey)
                require(input.tapInternalKey != null && xOnly.contentEquals(input.tapInternalKey)) {
                    "Internal key derivada não bate com a do PSBT no input $i — dado corrompido ou adulterado, assinatura recusada."
                }
                val expectedSpk = byteArrayOf(0x51, 0x20) + Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
                require(expectedSpk.contentEquals(utxo.scriptPubKey)) {
                    "scriptPubKey do input $i não bate com o esperado pra essa chave — assinatura recusada."
                }

                val sighash = TaprootSighashCalculator.calculate(tx = psbt.unsignedTx, inputIndex = i, utxos = allUtxos)
                psbt.inputs[i].tapKeySig = SchnorrSigner.sign(msg32 = sighash, privKey32 = tweakedPrivKey)
            } finally {
                tweakedPrivKey.fill(0)
                hdKey.privateKey.fill(0)
            }
        }

        val rawTxBytes = psbt.finalize()
        return rawTxBytes.joinToString("") { "%02x".format(it) } to psbt.unsignedTx.txid()
    }
}
