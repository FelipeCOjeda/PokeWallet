package com.pokewallet.cli

import com.pokewallet.crypto.*

/**
 * PokéWallet CLI
 *
 * Camada fina, explícita e honesta.
 *
 * Fonte da verdade para saldo / UTXOs:
 * - Bitcoin Core
 * - Descriptors (watch-only)
 *
 * Geração de endereço:
 * - Seed-based (APENAS educacional)
 */
object WalletCli {

    // =================================================
    // Contexto canônico de observação
    // =================================================

    /**
     * Prepara o ambiente de observação:
     *
     * - Lê wallet.json
     * - Garante wallet carregada no Bitcoin Core
     * - Importa descriptors (best-effort)
     * - Indexa UTXOs via Core
     */
    private fun observeWallet(): WalletUtxoIndex.Result {

        val ctx = WalletDescriptorContext.fromWalletJson()

        // 🔑 PASSO CRÍTICO: wallet PRECISA estar carregada
        ctx.ensureWalletLoaded()

        // 🧠 Best-effort: descriptors podem já existir
        try {
            ctx.ensureDescriptorsImported()
        } catch (e: Exception) {
            println("⚠️  Aviso: não foi possível importar descriptors automaticamente.")
            println("   Motivo: ${e.message}")
            println("   Prosseguindo com observação...\n")
        }

        // 🔎 Indexação real vem do Core
        return WalletUtxoIndex.index(
            rpc = ctx.rpc,
            externalDescriptor = ctx.externalDescriptor,
            internalDescriptor = ctx.internalDescriptor
        )
    }

    // =================================================
    // Commands
    // =================================================

    /**
     * 💰 BALANCE (CANÔNICO)
     *
     * Fonte da verdade:
     * - listunspent (Bitcoin Core)
     * - descriptors importados
     */
    fun balance() {

        val result = observeWallet()

        println("=== PokéWallet Balance (observado) ===")
        println("Total : ${result.totalSats} sats")
        println("UTXOs : ${result.utxos.size}")
    }

    /**
     * 🔎 UTXOS (CANÔNICO)
     *
     * Watch-only, descriptor-native.
     */
    fun utxos() {

        val result = observeWallet()

        println("=== PokéWallet UTXOs (observados) ===")

        if (result.utxos.isEmpty()) {
            println("Nenhum UTXO encontrado.")
            return
        }

        result.utxos.forEachIndexed { i, utxo ->
            println("#$i")
            println(" txid   : ${utxo.txid.joinToString("") { "%02x".format(it) }}")
            println(" vout   : ${utxo.vout}")
            println(" value  : ${utxo.valueSats} sats")
            println(
                " branch : ${utxo.branch} " +
                "(${if (utxo.branch == 0) "external" else "internal"})"
            )
            println(" index  : ${utxo.index}")
            println()
        }
    }

    /**
     * 📥 RECEIVE (EDUCACIONAL / SEED-BASED)
     *
     * ⚠️ NÃO é canônico.
     * ⚠️ NÃO reflete necessariamente o Core.
     *
     * Mantido apenas para fins didáticos e testes.
     */
    fun receive() {

        val wallet = WalletStorage.load()

        val seed = SeedDerivation.fromMnemonic(
            wallet.mnemonic,
            wallet.passphrase
        )

        val index = wallet.nextExternalIndex

        val address = ReceiveAddressService.addressAt(
            seed = seed,
            spendType = wallet.spendType,
            network = wallet.network,
            index = index
        )

        // Espelho local (educacional)
        wallet.nextExternalIndex = index + 1
        WalletStorage.save(wallet)

        println("📥 Receive address:")
        println(address)
        println("🔢 Derivation index: $index")
    }
}
