import com.pokewallet.cli.WalletCli
import com.pokewallet.commands.FeesCommand
import com.pokewallet.commands.ScanCommand
import com.pokewallet.commands.SendCommand
import com.pokewallet.crypto.DiceEntropy
import com.pokewallet.crypto.Network
import com.pokewallet.crypto.WalletInit
import com.pokewallet.crypto.WalletForget

fun main(args: Array<String>) {

    when (args.firstOrNull()) {

        "wallet-init"   -> {
            val diceArg = args.find { it.startsWith("--dice=") }?.substringAfter("--dice=")
            val diceRolls = diceArg?.let {
                DiceEntropy.parseRolls(it) ?: run {
                    println("❌ --dice inválido: use apenas dígitos de 1 a 6 (ex: --dice=351624...).")
                    return
                }
            }
            if (diceRolls != null && diceRolls.size < DiceEntropy.MIN_ROLLS) {
                println("❌ --dice precisa de pelo menos ${DiceEntropy.MIN_ROLLS} lançamentos (fornecidos: ${diceRolls.size}).")
                return
            }
            if (diceRolls != null && diceRolls.size < DiceEntropy.RECOMMENDED_ROLLS) {
                println("⚠️  Recomendado pelo menos ${DiceEntropy.RECOMMENDED_ROLLS} lançamentos pra entropia plena (fornecidos: ${diceRolls.size}, ~${"%.0f".format(DiceEntropy.entropyBits(diceRolls.size))} bits). Prosseguindo mesmo assim.")
            }
            WalletInit.run(
                network = if (args.contains("--testnet")) Network.TESTNET else Network.REGTEST,
                diceRolls = diceRolls
            )
        }
        "wallet-forget" -> WalletForget.run()

        // Comandos via Bitcoin Core (requer nó local)
        "balance"       -> WalletCli.balance()
        "utxos"         -> WalletCli.utxos()
        "receive"       -> WalletCli.receive()

        // Comandos via Blockstream API (sem nó)
        "scan"          -> ScanCommand.run(
                               verbose      = args.contains("--verbose"),
                               forceTestnet = args.contains("--testnet")
                           )
        "fees"          -> FeesCommand.run()
        "send"          -> SendCommand.run(args)

        else -> println("""
            PokéWallet CLI

            ── Configuração ──────────────────────────────
              wallet-init           Inicializa wallet.json + descriptors
              wallet-init --dice=351624...
                                    Mistura lançamentos de dado (d6) na
                                    entropia — mínimo 20, recomendado 99
              wallet-forget         Esquece a wallet local (DESTRUTIVO)

            ── Via Bitcoin Core (nó local) ───────────────
              balance               Saldo via descriptors
              utxos                 Lista UTXOs observados
              receive               Gera endereço de recebimento

            ── Via Blockstream API (sem nó) ──────────────
              scan                  Varre a wallet e mostra saldo real
              scan --verbose        Varre mostrando cada endereço
              scan --testnet        Força testnet (útil se wallet for REGTEST)
              fees                  Estimativas de taxa da rede
              send <addr> --sweep   Envia tudo (menos taxa) para o endereço
              send <addr> <sat>     Envia valor específico em satoshis

            ⚠️  wallet-forget NÃO apaga fundos.
        """.trimIndent())
    }
}
