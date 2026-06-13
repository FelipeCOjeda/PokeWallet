package com.pokewallet.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

object WalletInit {

    fun run(network: Network = Network.REGTEST) {

        println("=== PokeWallet — wallet-init ===\n")

        val walletFile = File(WalletStorage.filesDir, "wallet.json")
        if (walletFile.exists()) {
            println("❌ wallet.json já existe.")
            println("Abortando para evitar sobrescrita e perda de fundos.")
            return
        }

        // -----------------------------
        // Configuração base
        // -----------------------------
        val spendType = SpendType.BIP84

        // -----------------------------
        // Seed + mnemonic
        // -----------------------------
        val entropy = CryptoUtils.randomEntropy256()
        val mnemonicWords = Bip39.generateMnemonic(entropy)
        val mnemonic = mnemonicWords.joinToString(" ")
        val passphrase = PokemonPassphrase.choose(entropy)

        val seed = SeedDerivation.fromMnemonic(
            mnemonicWords,
            passphrase
        )

        // -----------------------------
        // Master fingerprint (BIP32)
        // -----------------------------
        val master = Bip32.fromSeed(seed)
        val fingerprintHex = Fingerprint.of(master).toFingerprintHex()

        // -----------------------------
        // Nome CANÔNICO da wallet
        // Regra: pokewallet_<fingerprint>
        // -----------------------------
        val walletName = "pokewallet_$fingerprintHex"

        // -----------------------------
        // Account key: m/84'/coin'/0'
        // -----------------------------
        val accountKey = KeyDerivation.derive(
            seed,
            intArrayOf(
                KeyDerivation.hardened(84),
                KeyDerivation.hardened(network.coinType.toInt()),
                KeyDerivation.hardened(0)
            )
        )

        // -----------------------------
        // XPUB
        // -----------------------------
        val xpub = XpubEncoder.encode(accountKey, network)

        // -----------------------------
        // Descriptors CANÔNICOS
        // -----------------------------
        val derivationPrefix =
            "[$fingerprintHex/84h/${network.coinType}h/0h]$xpub"

        val externalDescriptor = "wpkh($derivationPrefix/0/*)"
        val internalDescriptor = "wpkh($derivationPrefix/1/*)"

        // -----------------------------
        // Criação da wallet no Bitcoin Core
        // -----------------------------
        val rpcRoot = BitcoinRpcClient()

        try {
            rpcRoot.call(
                "createwallet",
                mapOf(
                    "wallet_name" to walletName,
                    "disable_private_keys" to true,
                    "blank" to true,
                    "descriptors" to true
                )
            )
            println("🧱 Wallet criada no Bitcoin Core: $walletName")
        } catch (_: Exception) {
            // wallet já existe → ok
        }

        // -----------------------------
        // Persistência (wallet.json)
        // -----------------------------
        val json = JSONObject()
            .put("version", 2)
            .put("walletName", walletName)
            .put("network", network.name)
            .put("spendType", spendType.name)
            .put("mnemonic", mnemonic)
            .put("passphrase", passphrase)
            .put("fingerprint", fingerprintHex)
            .put("xpub", xpub)
            .put("externalDescriptor", externalDescriptor)
            .put("internalDescriptor", internalDescriptor)
            .put("nextExternalIndex", 0)
            .put("nextInternalIndex", 0)
            .put("createdAt", Instant.now().toString())

        WalletStorage.saveRaw(json)

        // -----------------------------
        // Import automático dos descriptors
        // -----------------------------
        try {
            val rpc = BitcoinRpcClient(wallet = walletName)

            // garante que a wallet está carregada
            try {
                rpc.getWalletInfo()
            } catch (_: Exception) {
                rpcRoot.call("loadwallet", listOf(walletName))
            }

            fun buildReq(desc: String, internal: Boolean): JSONObject {
                val info = rpc.getDescriptorInfo(desc)
                val checksummed = info.getString("descriptor")

                return JSONObject()
                    .put("desc", checksummed)
                    .put("active", true)
                    .put("internal", internal)
                    .put("range", JSONArray(listOf(0, 1000)))
                    .put("timestamp", "now")
            }

            val reqs = listOf(
                buildReq(externalDescriptor, internal = false),
                buildReq(internalDescriptor, internal = true)
            )

            val result = rpc.importDescriptors(reqs)

            println("📡 Descriptors importados no Bitcoin Core:")
            println(result.toString(2))

        } catch (e: Exception) {
            println("⚠️ Não foi possível importar descriptors automaticamente.")
            println("Motivo: ${e.message}")
            println("Você pode importar manualmente depois, sem risco.")
        }

        // -----------------------------
        // Output final
        // -----------------------------
        println("\n✅ Wallet criada com sucesso!")
        println("Wallet name : $walletName")
        println("Network     : ${network.name}")
        println("Spend type  : ${spendType.name}")
        println("Fingerprint : $fingerprintHex")
        println("XPUB        : $xpub")

        println("\n⚠️  ATENÇÃO IMPORTANTE")
        println("Anote as 24 palavras E o Pokémon da passphrase.")
        println("Sem isso, NÃO existe recuperação possível.")

        println("\n📄 Arquivo criado: wallet.json")
        println("🚫 NÃO versionar esse arquivo.")
    }
}
