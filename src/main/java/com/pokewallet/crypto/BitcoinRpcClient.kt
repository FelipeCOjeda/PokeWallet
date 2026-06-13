package com.pokewallet.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class BitcoinRpcClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 18443,
    private val wallet: String? = null
) {

    private val rpcUrl: String =
        if (wallet != null)
            "http://$host:$port/wallet/$wallet"
        else
            "http://$host:$port/"

    // -------------------------------------------------
    // Cookie auth
    // -------------------------------------------------

    private val authHeader: String by lazy {
        val cookiePath = locateCookie()
        val cookie = cookiePath.toFile().readText().trim()
        val encoded = Base64.getEncoder()
            .encodeToString(cookie.toByteArray())
        "Basic $encoded"
    }

   private fun locateCookie(): Path {

    // 👇 Ajuste aqui para seu datadir real
    val explicit = Path.of("C:/Program Files/Bitcoin/daemon/regtest/.cookie")

    if (Files.exists(explicit)) {
        return explicit
    }

    error("Bitcoin Core .cookie not found at expected path.")
}

    // -------------------------------------------------
    // Core RPC
    // -------------------------------------------------

    fun call(method: String, params: Any = emptyList<Any>()): Any {

        val conn = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", authHeader)
        }

        val body = JSONObject()
            .put("jsonrpc", "1.0")
            .put("id", "pokewallet")
            .put("method", method)
            .put("params", params)

        conn.outputStream.use {
            it.write(body.toString().toByteArray())
        }

        val responseText = conn.inputStream.bufferedReader().readText()
        val response = JSONObject(responseText)

        if (!response.isNull("error")) {
            val err = response.getJSONObject("error")
            throw RuntimeException(
                "RPC error ${err.optInt("code")}: ${err.optString("message")}"
            )
        }

        return response.get("result")
    }

    // -------------------------------------------------
    // Thin wrappers
    // -------------------------------------------------

    fun listUnspent(): JSONArray =
        call("listunspent") as JSONArray

    fun getWalletInfo(): JSONObject =
        call("getwalletinfo") as JSONObject

    fun getDescriptorInfo(desc: String): JSONObject =
        call("getdescriptorinfo", listOf(desc)) as JSONObject

    fun importDescriptors(reqs: List<JSONObject>): JSONArray {

        val requestArray = JSONArray()
        for (r in reqs) {
            requestArray.put(r)
        }

        val wrapper = JSONArray()
        wrapper.put(requestArray)

        return call("importdescriptors", wrapper) as JSONArray
    }
}
