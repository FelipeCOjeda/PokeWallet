package com.pokewallet.crypto

import org.json.JSONObject
import java.time.Instant

object WalletRestore {

    private val wordSet: Set<String> by lazy { Bip39Wordlist.ENGLISH.toSet() }

    fun invalidWords(words: List<String>): List<String> =
        words.filter { it.isNotBlank() && it !in wordSet }

    fun run(words: List<String>, passphrase: String, network: Network) {
        require(words.size == 24) { "São necessárias exatamente 24 palavras." }

        val bad = invalidWords(words)
        require(bad.isEmpty()) { "Palavra(s) inválida(s): ${bad.take(3).joinToString(", ")}${if (bad.size > 3) "…" else ""}" }

        require(!WalletStorage.exists()) { "Wallet já existe. Use 'Esquecer Wallet' antes de restaurar." }

        val seed           = SeedDerivation.fromMnemonic(words, passphrase)
        val master         = Bip32.fromSeed(seed)
        val fingerprintHex = Fingerprint.of(master).toFingerprintHex()
        val walletName     = "pokewallet_$fingerprintHex"

        val accountKey = KeyDerivation.derive(
            seed,
            intArrayOf(
                KeyDerivation.hardened(84),
                KeyDerivation.hardened(network.coinType),
                KeyDerivation.hardened(0)
            )
        )

        val xpub                = XpubEncoder.encode(accountKey, network)
        val derivationPrefix    = "[$fingerprintHex/84h/${network.coinType}h/0h]$xpub"
        val externalDescriptor  = "wpkh($derivationPrefix/0/*)"
        val internalDescriptor  = "wpkh($derivationPrefix/1/*)"

        val json = JSONObject()
            .put("version",              2)
            .put("walletName",           walletName)
            .put("network",              network.name)
            .put("spendType",            SpendType.BIP84.name)
            .put("mnemonic",             words.joinToString(" "))
            .put("passphrase",           passphrase)
            .put("fingerprint",          fingerprintHex)
            .put("xpub",                 xpub)
            .put("externalDescriptor",   externalDescriptor)
            .put("internalDescriptor",   internalDescriptor)
            .put("nextExternalIndex",    0)
            .put("nextInternalIndex",    0)
            .put("createdAt",            Instant.now().toString())

        WalletStorage.saveRaw(json)
    }
}
