package com.pokewallet.network

import com.pokewallet.crypto.Network
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BlockstreamClient {

    /**
     * Provedores Esplora, em ORDEM de preferência — Blockstream e
     * mempool.space implementam a MESMA API (mesmos endpoints/formato de
     * resposta), então dá pra alternar entre eles sem mudar nenhum parsing.
     * Existe pra REDUNDÂNCIA: cada provedor tem seu próprio limite de rate
     * limit por IP (Blockstream: 700 req/hora — foi exatamente esse limite
     * que o app bateu em campo, ver getWithFallback()); se o primeiro
     * falhar (429, fora do ar, timeout), tenta o próximo antes de desistir.
     */
    private fun baseUrls(network: Network): List<String> = when (network) {
        Network.MAINNET -> listOf(
            "https://blockstream.info/api",
            "https://mempool.space/api",
            // Espelho Esplora comunitário (emzy, conhecido na comunidade
            // Bitcoin/Lightning alemã) — mesmo formato de resposta,
            // confirmado batendo os mesmos endpoints antes de entrar aqui.
            // 3ª camada: só é tentado se Blockstream E mempool.space
            // falharem os dois.
            "https://mempool.emzy.de/api"
        )
        Network.TESTNET -> listOf(
            "https://mempool.space/testnet/api",
            "https://blockstream.info/testnet/api"
            // mempool.emzy.de/testnet testado e fora do ar (502) no momento
            // desta mudança — não incluído até confirmar estabilidade.
        )
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
        val hour: Double,
        /** meta em nº de blocos -> sat/vB, direto da API — usado pra estimar
         *  tempo de confirmação de uma taxa arbitrária escolhida pelo usuário */
        val byBlockTarget: Map<Int, Double>
    ) {
        companion object {
            /** Usado quando a API está fora do ar — mantém a UI de taxa
             *  funcional (com estimativa aproximada) em vez de travar o envio. */
            val FALLBACK = FeeEstimates(
                fastest       = 20.0,
                halfHour      = 10.0,
                hour          = 5.0,
                byBlockTarget = mapOf(1 to 20.0, 3 to 10.0, 6 to 5.0)
            )
        }
    }

    data class BtcPrices(val usd: Double, val brl: Double)

    // ── API calls ─────────────────────────────────────

    fun getAddressStats(address: String, network: Network): AddressStats {
        val json    = JSONObject(getWithFallback(network, "/address/$address"))
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
        val array = JSONArray(getWithFallback(network, "/address/$address/utxo"))
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
        val json = JSONObject(getWithFallback(network, "/fee-estimates"))
        val byBlockTarget = sortedMapOf<Int, Double>()
        json.keys().forEach { key ->
            key.toIntOrNull()?.let { target -> byBlockTarget[target] = json.getDouble(key) }
        }
        return FeeEstimates(
            fastest       = json.optDouble("1",  20.0),
            halfHour      = json.optDouble("3",  10.0),
            hour          = json.optDouble("6",   5.0),
            byBlockTarget = byBlockTarget
        )
    }

    fun broadcast(rawHex: String, network: Network): String =
        postWithFallback(network, "/tx", rawHex).trim()

    fun getAddressTxs(address: String, network: Network): List<JSONObject> {
        return try {
            val arr = JSONArray(getWithFallback(network, "/address/$address/txs"))
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

    /**
     * Preço via Binance; se falhar (fora do ar, instável, rate limit),
     * cai pra CoinGecko antes de desistir — evita o preço ficar
     * indefinidamente null só porque UM provedor caiu.
     */
    fun getBtcPrices(): BtcPrices {
        return try {
            val usd = JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT")).getDouble("price")
            val brl = JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=BTCBRL")).getDouble("price")
            BtcPrices(usd, brl)
        } catch (_: Exception) {
            val coin = JSONObject(get("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd,brl"))
                .getJSONObject("bitcoin")
            BtcPrices(usd = coin.getDouble("usd"), brl = coin.getDouble("brl"))
        }
    }

    // ── Redundância entre provedores ──────────────────

    /**
     * Tenta cada provedor de [baseUrls] em ordem, caindo pro próximo em
     * QUALQUER falha (429, timeout, fora do ar) — não só rate limit
     * especificamente, redundância genérica é mais robusta. Só propaga
     * erro se TODOS os provedores falharem — a mensagem final lista o que
     * aconteceu com CADA UM (host: motivo), em vez de só o último, pra dar
     * diagnóstico de verdade em vez de "bateu rate limit" genérico quando
     * pode ser timeout/DNS/etc num provedor específico.
     */
    private fun getWithFallback(network: Network, path: String): String {
        val failures = mutableListOf<String>()
        for (base in baseUrls(network)) {
            try {
                return get(base + path)
            } catch (e: Exception) {
                failures += "${hostOf(base)}: ${e.message}"
            }
        }
        error("Todos os provedores falharam — ${failures.joinToString(" | ")}")
    }

    private fun postWithFallback(network: Network, path: String, body: String): String {
        val failures = mutableListOf<String>()
        for (base in baseUrls(network)) {
            try {
                return post(base + path, body)
            } catch (e: Exception) {
                failures += "${hostOf(base)}: ${e.message}"
            }
        }
        error("Todos os provedores falharam — ${failures.joinToString(" | ")}")
    }

    private fun hostOf(baseUrl: String): String = try {
        URL(baseUrl).host
    } catch (_: Exception) {
        baseUrl
    }

    // ── HTTP primitives ───────────────────────────────

    // NÃO chama conn.disconnect() no caminho de sucesso de propósito: um scan
    // faz dezenas de GETs sequenciais pro MESMO host (blockstream.info/
    // mempool.space) — disconnect() força descartar a conexão TLS em vez de
    // devolvê-la pro pool de keep-alive do Android, e cada request seguinte
    // paga handshake TCP+TLS inteiro do zero (o gargalo real reportado como
    // "demora demais"). Isso é diferente do problema já revertido antes
    // (commit b0a572d — esse era sobre abrir 8 conexões SIMULTÂNEAS e
    // esbarrar em limite de conexões concorrentes por host; aqui as
    // chamadas continuam sequenciais, só passam a reusar a mesma conexão
    // em vez de abrir uma nova a cada vez). disconnect() só no caminho de
    // erro, onde não faz sentido devolver uma conexão possivelmente quebrada
    // pro pool.
    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 15_000
        conn.requestMethod  = "GET"
        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()
            conn.disconnect()
            error("Blockstream API erro $code: $err")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
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
