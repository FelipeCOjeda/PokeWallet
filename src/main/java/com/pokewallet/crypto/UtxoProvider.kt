package com.pokewallet.crypto

import org.json.JSONArray
import java.util.HexFormat

/**
 * UtxoProvider
 *
 * Responsável por carregar UTXOs reais a partir do Bitcoin Core
 * via `bitcoin-cli listunspent`.
 *
 * Camada de I/O:
 * - NÃO faz coin selection
 * - NÃO faz validação criptográfica
 * - NÃO conhece chaves privadas
 * - NÃO interpreta derivação HD
 */
object UtxoProvider {

    /**
     * Carrega UTXOs spendable da wallet indicada no Bitcoin Core.
     *
     * Retorna UTXOs no formato interno da wallet,
     * prontos para coin selection e planejamento de transação.
     */
    fun loadFromBitcoinCore(wallet: WalletData): List<Utxo> {

        // ---------------------------------------------
        // bitcoin-cli wallet-scoped (fonte da verdade)
        // ---------------------------------------------
        val cli = BitcoinCli(wallet.walletName)

        // Já retorna JSONArray — NÃO embrulhar novamente
        val array: JSONArray = cli.listUnspent(
            minConf = 0,
            maxConf = 9999999,
            includeUnsafe = true
        )

        val utxos = mutableListOf<Utxo>()
        val hex = HexFormat.of()

        // ---------------------------------------------
        // Conversão para modelo interno
        // ---------------------------------------------
        for (i in 0 until array.length()) {

            val obj = array.getJSONObject(i)

            // Ignora UTXOs não spendable
            if (obj.has("spendable") && !obj.getBoolean("spendable")) {
                continue
            }

            val amountBtc = obj.getDouble("amount")
            if (amountBtc <= 0.0) continue

            val txidLE =
                hex.parseHex(obj.getString("txid"))
                    .reversedArray()

            val vout = obj.getInt("vout")
            val valueSats =
                (amountBtc * 100_000_000L).toLong()

            val scriptPubKey =
                hex.parseHex(obj.getString("scriptPubKey"))

            utxos += Utxo(
                txid = txidLE,
                vout = vout,
                value = valueSats,
                scriptPubKey = scriptPubKey
            )
        }

        return utxos
    }
}
