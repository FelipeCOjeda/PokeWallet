package com.pokewallet.crypto

/**
 * PsbtSigner
 *
 * Responsável por:
 *  - calcular sighash (BIP143)
 *  - assinar com chave privada
 *  - inserir partial signatures no PSBT
 *
 * Suporte atual:
 *  - SegWit v0
 *  - P2WPKH (BIP84)
 */
object PsbtSigner {

    /**
     * Assina um input específico do PSBT (SegWit v0 / P2WPKH)
     */
    fun signInput(
        psbt: Psbt,
        inputIndex: Int,
        privateKey: ByteArray
    ) {

        val input = psbt.inputs[inputIndex]
        val tx = psbt.unsignedTx

        val utxo = input.witnessUtxo
            ?: error("SegWit input requer witnessUtxo")

        // -------------------------------------------------
        // 1) scriptCode (BIP143)
        //    Para P2WPKH:
        //    OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG
        // -------------------------------------------------

        val pubKey = Secp256k1.publicKeyFromPrivate(privateKey)
        val pubKeyHash = Hashes.hash160(pubKey)
        val scriptCode = buildP2PKHScript(pubKeyHash)

        // -------------------------------------------------
        // 2) Sighash (BIP143)
        // -------------------------------------------------

        val sighash = SighashCalculator.segwitV0Sighash(
            tx = tx,
            inputIndex = inputIndex,
            scriptCode = scriptCode,
            inputValue = utxo.value,
            sighashType = SighashCalculator.SIGHASH_ALL
        )

        // -------------------------------------------------
        // 3) Assinatura ECDSA
        //    (DER + LOW-S, conforme Bitcoin Core)
        // -------------------------------------------------

        val derSignature = Secp256k1.sign(
            privateKey = privateKey,
            messageHash = sighash
        )

        // DER + sighash byte
        val sigWithHashType =
            derSignature + byteArrayOf(SighashCalculator.SIGHASH_ALL.toByte())

        // -------------------------------------------------
        // 4) Inserir partial signature no PSBT
        // -------------------------------------------------

        input.partialSignatures[pubKey] = sigWithHashType
    }

    // =================================================
    // Script helpers
    // =================================================

    /**
     * scriptCode para P2WPKH (BIP143)
     *
     * OP_DUP OP_HASH160 <20-byte> OP_EQUALVERIFY OP_CHECKSIG
     */
    private fun buildP2PKHScript(pubKeyHash: ByteArray): ByteArray {
        require(pubKeyHash.size == 20)

        return byteArrayOf(
            0x76.toByte(), // OP_DUP
            0xa9.toByte(), // OP_HASH160
            0x14.toByte()  // push 20 bytes
        ) + pubKeyHash + byteArrayOf(
            0x88.toByte(), // OP_EQUALVERIFY
            0xac.toByte()  // OP_CHECKSIG
        )
    }
}

