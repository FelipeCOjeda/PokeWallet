package com.pokewallet.network

import com.pokewallet.crypto.AddressCodec
import com.pokewallet.crypto.CryptoUtils
import com.pokewallet.crypto.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cliente do protocolo Electrum (JSON-RPC 2.0 sobre TCP, uma mensagem por
 * linha) — fala com um node próprio que exponha um servidor Electrum, como
 * o `florestad` do Floresta (127.0.0.1:50001 por padrão, sem TLS). Permite
 * escanear saldo/UTXOs e transmitir tx SEM depender de Blockstream/
 * mempool.space — só da rede local até o node.
 *
 * Mantém UM socket TCP persistente reaberto sob demanda: um scan faz
 * dezenas de chamadas sequenciais (uma por endereço), reusar a conexão
 * evita reconectar a cada request (mesmo raciocínio do keep-alive HTTP em
 * [BlockstreamClient]).
 */
class ElectrumClient(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) : ChainDataSource {

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var nextId = 1

    @Synchronized
    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
    }

    private fun ensureConnected() {
        val s = socket
        if (s != null && s.isConnected && !s.isClosed) return

        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        newSocket.soTimeout = readTimeoutMs
        socket = newSocket
        writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8))
        reader = BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8))
    }

    @Synchronized
    private fun call(method: String, params: JSONArray): JSONObject {
        try {
            ensureConnected()
            val id = nextId++
            val request = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)

            writer!!.write(request.toString())
            writer!!.write("\n")
            writer!!.flush()

            val line = reader!!.readLine()
                ?: error("Electrum server $host:$port fechou a conexão sem responder ($method)")
            val response = JSONObject(line)
            if (response.has("error") && !response.isNull("error")) {
                error("Electrum RPC erro em $method: ${response.get("error")}")
            }
            return response
        } catch (e: Exception) {
            close()
            if (e.message?.startsWith("Electrum") == true) throw e
            throw RuntimeException("Falha ao falar com Electrum server $host:$port ($method): ${e.message}", e)
        }
    }

    /** Scripthash Electrum: SHA256(scriptPubKey) com bytes revertidos, em hex. */
    fun scripthashOf(address: String, network: Network): String {
        val spk  = AddressCodec.addressToScriptPubKey(address, network)
        val hash = CryptoUtils.sha256(spk)
        return hash.reversedArray().joinToString("") { "%02x".format(it) }
    }

    // ── ChainDataSource ──────────────────────────────────

    override fun getAddressStats(address: String, network: Network): AddressStats {
        val scripthash = scripthashOf(address, network)

        val balance = call("blockchain.scripthash.get_balance", JSONArray(listOf(scripthash)))
            .getJSONObject("result")
        // optLong (não getLong): um node ainda sincronizando (ex.: Floresta em
        // IBD) pode devolver "confirmed"/"unconfirmed" como null pra um
        // scripthash que ele ainda não indexou — tratar como 0 em vez de
        // derrubar o scan inteiro com JSONException.
        val confirmedSats = balance.optLong("confirmed", 0L)
        val pendingSats    = balance.optLong("unconfirmed", 0L)

        val history = call("blockchain.scripthash.get_history", JSONArray(listOf(scripthash)))
            .getJSONArray("result")
        var confirmedTxCount = 0
        var mempoolTxCount   = 0
        for (i in 0 until history.length()) {
            val height = history.getJSONObject(i).getInt("height")
            if (height > 0) confirmedTxCount++ else mempoolTxCount++
        }

        return AddressStats(
            address        = address,
            confirmedSats  = confirmedSats,
            pendingSats    = pendingSats,
            txCount        = confirmedTxCount,
            mempoolTxCount = mempoolTxCount
        )
    }

    override fun getUtxos(address: String, network: Network): List<RemoteUtxo> {
        val scripthash = scripthashOf(address, network)
        val result = call("blockchain.scripthash.listunspent", JSONArray(listOf(scripthash)))
            .getJSONArray("result")

        return (0 until result.length()).map { i ->
            val obj    = result.getJSONObject(i)
            val height = obj.getInt("height")
            RemoteUtxo(
                txid        = obj.getString("tx_hash"),
                vout        = obj.getInt("tx_pos"),
                valueSats   = obj.getLong("value"),
                confirmed   = height > 0,
                blockHeight = if (height > 0) height else null
            )
        }
    }

    override fun getFeeEstimates(network: Network): FeeEstimates {
        // blockchain.estimatefee retorna BTC/kB pro alvo de blocos pedido;
        // -1 significa "não deu pra estimar" (node com pouco mempool/recém
        // sincronizado) — cai pro valor de fallback correspondente.
        fun estimateSatPerVb(target: Int): Double {
            val btcPerKb = call("blockchain.estimatefee", JSONArray(listOf(target))).getDouble("result")
            if (btcPerKb <= 0) return FeeEstimates.FALLBACK.byBlockTarget[target] ?: 1.0
            return (btcPerKb * 100_000_000.0) / 1000.0
        }

        val fastest  = estimateSatPerVb(1)
        val halfHour = estimateSatPerVb(3)
        val hour     = estimateSatPerVb(6)

        return FeeEstimates(
            fastest       = fastest,
            halfHour      = halfHour,
            hour          = hour,
            byBlockTarget = mapOf(1 to fastest, 3 to halfHour, 6 to hour)
        )
    }

    override fun broadcast(rawHex: String, network: Network): String =
        call("blockchain.transaction.broadcast", JSONArray(listOf(rawHex))).getString("result")
}
