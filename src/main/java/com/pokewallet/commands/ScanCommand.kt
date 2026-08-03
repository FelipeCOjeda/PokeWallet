package com.pokewallet.commands

import com.pokewallet.crypto.Network
import com.pokewallet.crypto.WalletStorage
import com.pokewallet.network.BlockstreamClient
import com.pokewallet.network.WalletScanner
import kotlinx.coroutines.runBlocking

/**
 * scan — Varre a wallet via Blockstream API (sem Bitcoin Core).
 *
 * Usa o xpub armazenado no wallet.json + gap limit BIP44.
 * Atualiza nextExternalIndex e nextInternalIndex no wallet.json.
 */
object ScanCommand {

    fun run(verbose: Boolean = false, forceTestnet: Boolean = false) {

        val wallet = WalletStorage.load()

        val xpub = requireNotNull(wallet.xpub) {
            "xpub não encontrado no wallet.json. Rode wallet-init primeiro."
        }

        val network = when {
            forceTestnet -> Network.TESTNET
            wallet.network == Network.REGTEST -> {
                println("⚠️  Rede REGTEST não é suportada pela Blockstream API pública.")
                println("   REGTEST é uma rede local — não existe na internet.")
                println()
                println("   Opções:")
                println("   1. Rode com --testnet para derivar endereços testnet com o mesmo xpub")
                println("   2. Inicialize uma nova wallet com network=TESTNET ou MAINNET")
                return
            }
            else -> wallet.network
        }

        println("🔍 Varrendo wallet ${wallet.walletName} na rede ${network.name}...")
        println("   xpub: ${xpub.take(20)}…${xpub.takeLast(8)}")
        println("   gap limit: ${WalletScanner.GAP_LIMIT_DEFAULT}")
        if (forceTestnet && wallet.network == Network.REGTEST)
            println("   ⚠️  Modo --testnet: endereços tb1q (não bcrt1q)")
        println()

        val result = runBlocking {
            WalletScanner.scan(
                xpub     = xpub,
                network  = network,
                onProgress = { chain, index, address ->
                    if (verbose) {
                        val label = if (chain == 0) "ext" else "int"
                        print("  [$label/$index] $address\r")
                    }
                }
            )
        }

        if (verbose) println()

        // Atualiza índices no wallet.json
        wallet.nextExternalIndex = result.nextExternalIndex
        wallet.nextInternalIndex = result.nextInternalIndex
        WalletStorage.save(wallet)

        result.printSummary()
    }
}

/**
 * fees — Exibe estimativas de taxa da rede via Blockstream API.
 */
object FeesCommand {

    fun run() {
        val wallet = WalletStorage.load()
        val fees   = BlockstreamClient.getFeeEstimates(wallet.network)

        println()
        println("⚡ Taxas estimadas — ${wallet.network.name}")
        println("  Próximo bloco (~10 min) : ${"%.1f sat/vB".format(fees.fastest)}")
        println("  ~30 minutos             : ${"%.1f sat/vB".format(fees.halfHour)}")
        println("  ~1 hora                 : ${"%.1f sat/vB".format(fees.hour)}")
        println()
        println("  Uma transação P2WPKH típica (~141 vBytes):")
        println("  Próximo bloco : ${"%.0f sat".format(fees.fastest * 141)}")
        println("  ~30 min       : ${"%.0f sat".format(fees.halfHour * 141)}")
        println("  ~1 hora       : ${"%.0f sat".format(fees.hour * 141)}")
    }
}
