package com.pokewallet.crypto

import java.util.HexFormat
import org.json.JSONArray

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
 */
object UtxoProvider {

    /**
     * Carrega UTXOs spendable da wallet ativa no Bitcoin Core.
     *
     * Retorna UTXOs no formato interno da wallet,
     * prontos para coin selection.
     */
    fun loadFromBitcoinCore(): List<Utxo> {

        val json = BitcoinCli.listUnspent()
        val array = JSONArray(json)

        val utxos = mutableListOf<Utxo>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)

            // Ignora UTXOs não spendable (watch-only sem chave, por exemplo)
            if (obj.has("spendable") && !obj.getBoolean("spendable")) {
                continue
            }

            val amountBtc = obj.getDouble("amount")
            if (amountBtc <= 0.0) continue

            val txidLE = HexFormat.of()
                .parseHex(obj.getString("txid"))
                .reversedArray()

            val vout = obj.getInt("vout")

            val valueSats = (amountBtc * 100_000_000L).toLong()

            val scriptPubKey =
                HexFormat.of().parseHex(obj.getString("scriptPubKey"))

            utxos.add(
                Utxo(
                    txid = txidLE,
                    vout = vout,
                    value = valueSats,
                    scriptPubKey = scriptPubKey
                )
            )
        }

        return utxos
    }
}

