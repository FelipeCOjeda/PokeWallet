import com.pokewallet.crypto.*
import java.util.HexFormat
import java.io.File

fun main() {

    println("=== PokeWallet / Bitcoin HD Wallet (regtest) ===\n")

    // =================================================
    // Helpers — índice de change incremental
    // =================================================

    fun loadChangeIndex(): Int {
        val file = File("change_index.txt")
        if (!file.exists()) {
            file.writeText("0")
            return 0
        }
        return file.readText().trim().toInt()
    }

    fun saveChangeIndex(index: Int) {
        File("change_index.txt").writeText(index.toString())
    }

    // =================================================
    // 1) Seed
    // =================================================

    val entropy = CryptoUtils.randomEntropy256()
    val mnemonic = Bip39.generateMnemonic(entropy)
    val passphrase = PokemonPassphrase.choose(entropy)

    val seed = SeedDerivation.fromMnemonic(mnemonic, passphrase)

    println("Mnemonic:")
    println(mnemonic.joinToString(" "))
    println("\nPokemon passphrase:")
    println(passphrase)
    println("\nSeed OK\n")

    // =================================================
    // 2) Master fingerprint
    // =================================================

    val master = Bip32.fromSeed(seed)
    val fingerprint = Fingerprint.of(master)

    println("Master fingerprint:")
    println(fingerprint)
    println()

    // =================================================
    // 3) Import automático de descriptors BIP84
    // =================================================
    // IMPORTANTE:
    // - assume que DescriptorImporter está compilando
    // - operação é idempotente do ponto de vista lógico
    // =================================================

    DescriptorImporter.importBip84Descriptors(
        seed = seed,
        fingerprint = fingerprint
    )

    println("Descriptors BIP84 importados no Bitcoin Core\n")

    // =================================================
    // 4) Chave EXTERNAL (pagamento)
    // m/84'/1'/0'/0/0
    // =================================================

    val spendPath = intArrayOf(
        84 or 0x80000000.toInt(),
        1  or 0x80000000.toInt(),
        0  or 0x80000000.toInt(),
        0,
        0
    )

    val spendKey = KeyDerivation.derive(seed, spendPath)
    val privateKey = spendKey.privateKey
    val publicKey = Secp256k1.publicKeyFromPrivate(privateKey)
    val pubKeyHash = Hashes.hash160(publicKey)

    println("Derived pubkey:")
    println(HexFormat.of().formatHex(publicKey))
    println()

    // =================================================
    // 5) Change key (índice incremental)
    // m/84'/1'/0'/1/i
    // =================================================

    val changeIndex = loadChangeIndex()

    val changePath = intArrayOf(
        84 or 0x80000000.toInt(),
        1  or 0x80000000.toInt(),
        0  or 0x80000000.toInt(),
        1,
        changeIndex
    )

    val changeKey = KeyDerivation.derive(seed, changePath)
    val changePubKey = Secp256k1.publicKeyFromPrivate(changeKey.privateKey)
    val changePubKeyHash = Hashes.hash160(changePubKey)

    println("Change index usado: $changeIndex\n")

    // =================================================
    // 6) Script helper (P2WPKH)
    // =================================================

    fun p2wpkhScript(hash: ByteArray): ByteArray =
        byteArrayOf(0x00, 0x14) + hash

    // =================================================
    // 7) UTXOs disponíveis (Bitcoin Core)
    // =================================================

    val availableUtxos = UtxoProvider.loadFromBitcoinCore()

    require(availableUtxos.isNotEmpty()) {
        "Nenhum UTXO disponível na wallet do Bitcoin Core"
    }

    println("UTXOs disponíveis: ${availableUtxos.size}")

    // =================================================
    // 8) Valores + coin selection
    // =================================================

    val sendValue = 49_999_000_00L
    val feeRateSatPerVbyte = 5L

    val (selectedUtxos, changeValue) = CoinSelector.select(
        utxos = availableUtxos,
        targetValue = sendValue,
        feeRateSatPerVbyte = feeRateSatPerVbyte
    )

    println("UTXOs selecionados: ${selectedUtxos.size}")
    println("Change calculado: $changeValue sats\n")

    // =================================================
    // 9) Inputs
    // =================================================

    val inputs = selectedUtxos.map { utxo ->
        TxIn(
            prevTxId = utxo.txid,
            prevIndex = utxo.vout,
            scriptSig = byteArrayOf(),
            sequence = 0xFFFFFFFFL
        )
    }

    // =================================================
    // 10) Outputs
    // =================================================

    val outputs = mutableListOf(
        TxOut(
            value = sendValue,
            scriptPubKey = p2wpkhScript(pubKeyHash)
        )
    )

    if (changeValue > 0) {
        outputs.add(
            TxOut(
                value = changeValue,
                scriptPubKey = p2wpkhScript(changePubKeyHash)
            )
        )
    }

    // =================================================
    // 11) Unsigned transaction
    // =================================================

    val unsignedTx = UnsignedTransaction(
        version = 2,
        inputs = inputs,
        outputs = outputs,
        lockTime = 0
    )

    // =================================================
    // 12) PSBT
    // =================================================

    val psbt = Psbt(
        unsignedTx = unsignedTx,
        inputs = MutableList(inputs.size) { PsbtInput() },
        outputs = MutableList(outputs.size) { PsbtOutput() }
    )

    selectedUtxos.forEachIndexed { index, utxo ->
        psbt.inputs[index].witnessUtxo = TxOut(
            value = utxo.value,
            scriptPubKey = utxo.scriptPubKey
        )
    }

    // =================================================
    // 13) Assinar inputs (BIP143)
    // =================================================

    selectedUtxos.forEachIndexed { index, utxo ->
        val sig = SegwitSigner.sign(
            unsignedTx = unsignedTx,
            inputIndex = index,
            utxoValue = utxo.value,
            scriptPubKey = utxo.scriptPubKey,
            privateKey = privateKey
        )

        psbt.inputs[index].partialSignatures[publicKey] = sig
    }

    // =================================================
    // 14) Finalizar e broadcast
    // =================================================

    val finalTxBytes = psbt.finalize()
    val rawTxHex = HexFormat.of().formatHex(finalTxBytes)

    println("✅ Transação assinada\n")
    println("Raw transaction hex:\n$rawTxHex\n")

    println("Broadcast:")
    println("""bitcoin-cli -regtest sendrawtransaction "$rawTxHex"""")

    // =================================================
    // 15) Avançar índice de change
    // =================================================

    saveChangeIndex(changeIndex + 1)
    println("\nChange index incrementado para ${changeIndex + 1}")
}
