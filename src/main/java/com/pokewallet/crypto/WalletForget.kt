package com.pokewallet.crypto

import java.io.File

/**
 * Comando DESTRUTIVO e EXPLÍCITO.
 *
 * Responsabilidade única:
 * - Apagar o estado local da wallet (wallet.json)
 *
 * NÃO FAZ:
 * - Não cria wallet
 * - Não altera Bitcoin Core
 * - Não apaga fundos
 * - Não tenta "adivinhar" nada
 *
 * Filosofia:
 * Autocustódia exige atos conscientes.
 */
object WalletForget {

    private const val CONFIRMATION_PHRASE = "EU SEI O QUE ESTOU FAZENDO"

    fun run() {

        println("⚠️  ATENÇÃO — OPERAÇÃO DESTRUTIVA\n")

        val walletFile = File(WalletStorage.filesDir, "wallet.json")

        if (!walletFile.exists()) {
            println("❌ Nenhuma wallet encontrada neste diretório.")
            println("Nada para esquecer.")
            return
        }

        // -----------------------------
        // Leitura mínima para contexto (via WalletStorage — respeita
        // a criptografia AES-GCM do wallet.json, não lê o arquivo cru)
        // -----------------------------
        val wallet = try {
            WalletStorage.load()
        } catch (e: Exception) {
            println("❌ wallet.json está corrompido, ilegível ou não pôde ser descriptografado.")
            println("Apague manualmente se tiver certeza do que está fazendo.")
            return
        }

        val walletName = wallet.walletName
        val fingerprint = wallet.fingerprint

        println("Wallet encontrada:")
        println("  Nome        : $walletName")
        println("  Fingerprint : $fingerprint\n")

        println("Isso irá:")
        println("- apagar o arquivo wallet.json")
        println("- remover a wallet da memória local do PokéWallet")
        println("- NÃO apagar a wallet do Bitcoin Core")
        println("- NÃO apagar seus fundos")
        println("- exigir seed + Pokémon da passphrase para recuperação\n")

        println("⚠️  SEM MNEMONIC + POKÉMON, OS FUNDOS SÃO IRRECUPERÁVEIS.\n")

        print("Digite exatamente: $CONFIRMATION_PHRASE\n> ")

        val input = try {
            readln().trim()
        } catch (e: Exception) {
            println("\n❌ Entrada inválida. Operação abortada.")
            return
        }

        if (input != CONFIRMATION_PHRASE) {
            println("\n❌ Frase incorreta.")
            println("Operação cancelada.")
            return
        }

        // -----------------------------
        // Destruição consciente
        // -----------------------------
        val deleted = walletFile.delete()

        if (!deleted) {
            println("\n❌ Falha ao remover wallet.json.")
            println("Verifique permissões do sistema.")
            return
        }

        println("\n🧨 Wallet esquecida com sucesso.")
        println("Estado local removido.")
        println("Fundos permanecem intactos no Bitcoin Core.")
        println("Recuperação SOMENTE via mnemonic + Pokémon.")
    }
}
