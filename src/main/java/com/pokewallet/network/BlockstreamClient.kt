package com.pokewallet.network

import com.pokewallet.crypto.Network
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BlockstreamClient {

    fun baseUrl(network: Network): String = when (network) {
        Network.MAINNET -> "https://blockstream.info/api"
        Network.TESTNET -> "https://mempool.space/testnet/api"
        Network.REGTEST -> error("REGTEST não possui API pública — use TESTNET ou MAINNET")
    }

    // ── Models ────────────────────────────────────────

    data class AddressStats(
        val address: String,
        val fundedTxoCount: Int,
        val fundedTxoSum: Long,
        val spentTxoCount: Int,
        val spentTxoSum: Long,
        val txCount: Int,
        val mempoolFundedSum: Long,
        val mempoolSpentSum: Long,
        val mempoolTxCount: Int
    ) {
        val confirmedSats: Long get() = fundedTxoSum - spentTxoSum
        val pendingSats: Long get() = mempoolFundedSum - mempoolSpentSum
        val balanceSats: Long get() = confirmedSats + pendingSats
        val hasActivity: Boolean get() = txCount > 0 || mempoolTxCount > 0
    }

    data class Utxo(
        val txid: String,
        val vout: Int,
        val valueSats: Long,
        val confirmed: Boolean,
        val blockHeight: Int?
    )

    data class FeeEstimates(
        val fastest: Double,
        val halfHour: Double,
        val hour: Double
    )

    data class BtcPrices(val usd: Double, val brl: Double)

    // ── API calls ─────────────────────────────────────

    fun getAddressStats(address: String, network: Network): AddressStats {
        val json    = JSONObject(get("${baseUrl(network)}/address/$address"))
        val chain   = json.getJSONObject("chain_stats")
        val mempool = json.getJSONObject("mempool_stats")
        return AddressStats(
            address          = address,
            fundedTxoCount   = chain.getInt("funded_txo_count"),
            fundedTxoSum     = chain.getLong("funded_txo_sum"),
            spentTxoCount    = chain.getInt("spent_txo_count"),
            spentTxoSum      = chain.getLong("spent_txo_sum"),
            txCount          = chain.getInt("tx_count"),
            mempoolFundedSum = mempool.getLong("funded_txo_sum"),
            mempoolSpentSum  = mempool.getLong("spent_txo_sum"),
            mempoolTxCount   = mempool.getInt("tx_count")
        )
    }

    fun getUtxos(address: String, network: Network): List<Utxo> {
        val array = JSONArray(get("${baseUrl(network)}/address/$address/utxo"))
        return (0 until array.length()).map { i ->
            val obj    = array.getJSONObject(i)
            val status = obj.getJSONObject("status")
            Utxo(
                txid        = obj.getString("txid"),
                vout        = obj.getInt("vout"),
                valueSats   = obj.getLong("value"),
                confirmed   = status.getBoolean("confirmed"),
                blockHeight = if (status.getBoolean("confirmed")) status.optInt("block_height") else null
            )
        }
    }

    fun getFeeEstimates(network: Network): FeeEstimates {
        val json = JSONObject(get("${baseUrl(network)}/fee-estimates"))
        return FeeEstimates(
            fastest  = json.optDouble("1",  20.0),
            halfHour = json.optDouble("3",  10.0),
            hour     = json.optDouble("6",   5.0)
        )
    }

    fun broadcast(rawHex: String, network: Network): String =
        post("${baseUrl(network)}/tx", rawHex).trim()

    fun getAddressTxs(address: String, network: Network): List<JSONObject> {
        return try {
            val arr = JSONArray(get("${baseUrl(network)}/address/$address/txs"))
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun calcNetSats(txJson: JSONObject, address: String): Long {
        var received = 0L
        var spent    = 0L
        val vout = txJson.getJSONArray("vout")
        for (i in 0 until vout.length()) {
            val out = vout.getJSONObject(i)
            if (out.optString("scriptpubkey_address") == address) received += out.getLong("value")
        }
        val vin = txJson.getJSONArray("vin")
        for (i in 0 until vin.length()) {
            val prevout = vin.getJSONObject(i).optJSONObject("prevout")
            if (prevout?.optString("scriptpubkey_address") == address) spent += prevout.getLong("value")
        }
        return received - spent
    }

    fun getBtcPrices(): BtcPrices {
        val usd = JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT")).getDouble("price")
        val brl = JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=BTCBRL")).getDouble("price")
        return BtcPrices(usd, brl)
    }

    // ── HTTP primitives ───────────────────────────────

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 15_000
        conn.requestMethod  = "GET"
        return try {
            val code = conn.responseCode
            check(code in 200..299) {
                "Blockstream API erro $code: ${conn.errorStream?.bufferedReader()?.readText()}"
            }
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout    = 10_000
        conn.readTimeout       = 30_000
        conn.requestMethod     = "POST"
        conn.doOutput          = true
        conn.setRequestProperty("Content-Type", "text/plain")
        conn.outputStream.bufferedWriter().use { it.write(body) }
        return try {
            val code = conn.responseCode
            check(code == 200) {
                "Broadcast erro $code: ${conn.errorStream?.bufferedReader()?.readText()}"
            }
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
