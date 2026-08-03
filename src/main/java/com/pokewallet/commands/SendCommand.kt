package com.pokewallet.commands

import com.pokewallet.crypto.*
import com.pokewallet.network.BlockstreamClient
import com.pokewallet.network.WalletScanner

object SendCommand {

    fun run(args: Array<String>) {
        val destination = args.getOrNull(1)
            ?: error("Uso: send <endereço> <valor_sat|--sweep> [--testnet]")
        val sweep       = args.contains("--sweep")
        val forceTestnet = args.contains("--testnet")

        val wallet  = WalletStorage.load()
        val xpub    = requireNotNull(wallet.xpub) { "xpub não encontrado — rode wallet-init primeiro" }
        val network = when {
            forceTestnet               -> com.pokewallet.crypto.Network.TESTNET
            wallet.network == com.pokewallet.crypto.Network.REGTEST -> {
                println("ℹ️  Carteira REGTEST — usando rede TESTNET para API")
                com.pokewallet.crypto.Network.TESTNET
            }
            else -> wallet.network
        }

        // ── Seed ───────────────────────────────────────────────────
        val seed = SeedDerivation.fromMnemonic(wallet.mnemonic, wallet.passphrase)
        try {
            runSend(args, destination, sweep, xpub, network, seed)
        } finally {
            seed.fill(0)
        }
    }

    private fun runSend(
        args: Array<String>,
        destination: String,
        sweep: Boolean,
        xpub: String,
        network: com.pokewallet.crypto.Network,
        seed: ByteArray
    ) {
        // ── Scan UTXOs ────────────────────────────────────────────
        println("🔍 Varrendo UTXOs na rede ${network.name}...")
        val scanResult = WalletScanner.scan(xpub = xpub, network = network)

        if (scanResult.totalSats == 0L) {
            println("❌ Saldo zero. Nada para enviar.")
            return
        }

        // ── Fee rate ──────────────────────────────────────────────
        val fees     = BlockstreamClient.getFeeEstimates(network)
        val feeRate  = fees.halfHour.coerceAtLeast(1.0)

        // ── Build spendable UTXO list ─────────────────────────────
        data class SpendableUtxo(
            val txidLE: ByteArray,
            val vout: Int,
            val valueSats: Long,
            val scriptPubKey: ByteArray,
            val privateKey: ByteArray,
            val pubKey: ByteArray
        )

        val spendable = mutableListOf<SpendableUtxo>()

        for (addr in scanResult.addressesWithFunds) {
            val hdKey   = KeyDerivation.bip84(seed, coin = network.coinType, account = 0,
                change = addr.chain, address = addr.index)
            val privKey = hdKey.privateKey
            val pubKey  = Secp256k1.publicKeyFromPrivate(privKey)
            val pkh     = Hashes.hash160(pubKey)
            val spk     = byteArrayOf(0x00, 0x14) + pkh

            for (utxo in addr.utxos) {
                spendable += SpendableUtxo(
                    txidLE      = hexToBytes(utxo.txid).reversedArray(),
                    vout        = utxo.vout,
                    valueSats   = utxo.valueSats,
                    scriptPubKey = spk,
                    privateKey  = privKey,
                    pubKey      = pubKey
                )
            }
        }

        try {
            val totalInputSats = spendable.sumOf { it.valueSats }

            // ── Destination scriptPubKey ──────────────────────────────
            val destSpk = addressToScriptPubKey(destination)

            // ── Amount + troco ──────────────────────────────────────────
            val requestedAmount: Long? = if (sweep) {
                null
            } else {
                args.getOrNull(2)?.toLong()
                    ?: error("Uso: send <endereço> <valor_sat> [--testnet]   ou   send <endereço> --sweep [--testnet]")
            }
            val plan = ChangePlanner.plan(
                totalInputSats     = totalInputSats,
                requestedAmount    = requestedAmount,
                sweep              = sweep,
                inputCount         = spendable.size,
                feeRateSatPerVbyte = feeRate
            )
            val sendAmount = plan.sendAmount

            println("💸 Enviando $sendAmount sat  (@ $feeRate sat/vB)")
            println("   Para: $destination")
            println("   UTXOs: ${spendable.size}  |  Total disponível: $totalInputSats sat")

            // ── Troco (se houver) ────────────────────────────────────
            val txOutputs: List<TxOut> = if (plan.changeValue != null) {
                val changeIndex = WalletStorage.reserveNextInternalIndex()
                val changeHdKey = KeyDerivation.bip84(seed, coin = network.coinType, account = 0,
                    change = 1, address = changeIndex)
                try {
                    val changePubKey = Secp256k1.publicKeyFromPrivate(changeHdKey.privateKey)
                    val changeSpk = byteArrayOf(0x00, 0x14) + Hashes.hash160(changePubKey)
                    println("   Troco: ${plan.changeValue} sat (índice interno $changeIndex)")
                    listOf(TxOut(sendAmount, destSpk), TxOut(plan.changeValue, changeSpk))
                } finally {
                    changeHdKey.privateKey.fill(0)
                }
            } else {
                listOf(TxOut(sendAmount, destSpk))
            }

            // ── Build UnsignedTransaction ─────────────────────────────
            val txInputs = spendable.map { s ->
                TxIn(prevTxId = s.txidLE, prevIndex = s.vout,
                     scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)
            }
            val unsignedTx = UnsignedTransaction(version = 2, inputs = txInputs,
                outputs = txOutputs, lockTime = 0L)

            // ── Build and sign PSBT ───────────────────────────────────
            val psbt = Psbt(
                unsignedTx = unsignedTx,
                inputs     = MutableList(txInputs.size) { PsbtInput() },
                outputs    = MutableList(txOutputs.size) { PsbtOutput() }
            )

            spendable.forEachIndexed { i, s ->
                val sig = SegwitSigner.sign(
                    unsignedTx  = unsignedTx,
                    inputIndex  = i,
                    utxoValue   = s.valueSats,
                    scriptPubKey = s.scriptPubKey,
                    privateKey  = s.privateKey
                )
                psbt.inputs[i].witnessUtxo = TxOut(s.valueSats, s.scriptPubKey)
                psbt.inputs[i].partialSignatures[s.pubKey.toHex()] = sig
            }

            // ── Finalize and broadcast ────────────────────────────────
            val rawTxBytes = psbt.finalize()
            val rawTxHex   = rawTxBytes.joinToString("") { "%02x".format(it) }

            println("📡 Broadcasting (${rawTxBytes.size} bytes)...")
            val txid = BlockstreamClient.broadcast(rawTxHex, network)

            println()
            println("✅ Transação enviada com sucesso!")
            println("   txid : $txid")
            val explorerBase = if (network == com.pokewallet.crypto.Network.TESTNET) "https://mempool.space/testnet/tx/" else "https://mempool.space/tx/"
            println("   link : $explorerBase$txid")
        } finally {
            spendable.forEach { it.privateKey.fill(0) }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun addressToScriptPubKey(address: String): ByteArray {
        val (_, data) = Bech32.decode(address)
            ?: error("Endereço bech32 inválido: $address")
        require(data.isNotEmpty()) { "Endereço bech32 vazio" }

        val witnessVersion = data[0]
        val program5bit    = data.copyOfRange(1, data.size)

        // IntArray de 5-bit → ByteArray → convertBits → ByteArray 8-bit
        val prog5Bytes = ByteArray(program5bit.size) { program5bit[it].toByte() }
        val progInts   = Bech32.convertBits(prog5Bytes, 5, 8, false)
        val programBytes = ByteArray(progInts.size) { progInts[it].toByte() }

        val versionOpcode = if (witnessVersion == 0) 0x00.toByte() else (0x50 + witnessVersion).toByte()
        return byteArrayOf(versionOpcode, programBytes.size.toByte()) + programBytes
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        return ByteArray(len / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
