package com.pokewallet.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * [Dns] do OkHttp que tenta o DNS do sistema primeiro e, se falhar com
 * [UnknownHostException] (cenário real de rede que bloqueia domínios via
 * DNS — ex.: oracle.setor.dev/blockstream.info em campo), resolve via
 * DNS-over-HTTPS (dns.google, API JSON) num cliente separado que usa o DNS
 * PADRÃO do OkHttp — evitando recursão infinita.
 *
 * Cache curto em memória pra não repetir a consulta DoH a cada conexão
 * dentro do mesmo sync. Sem persistência: é só um fallback de resolução,
 * não um cache DNS de longa duração.
 */
class DohFallbackDns : Dns {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<InetAddress>>()

    private data class DohProvider(
        val baseUrl: String,
        val requiresJsonHeader: Boolean
    )

    /** Mais de um provedor: DNS-over-HTTPS do Google costuma funcionar, mas
     *  alguns provedores/rede móvel bloqueiam `dns.google`; Cloudflare é o
     *  segundo plano. */
    private val providers = listOf(
        DohProvider(baseUrl = "https://dns.google/resolve", requiresJsonHeader = false),
        DohProvider(baseUrl = "https://cloudflare-dns.com/dns-query", requiresJsonHeader = true)
    )

    // Cliente dedicado pro DoH — usa o Dns padrão do OkHttp (sistema), nunca
    // este objeto, senão a falha de resolver dns.google entraria em loop.
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { return it }
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            resolveViaDoh(hostname) ?: throw e
        }.also { cache[hostname] = it }
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress>? {
        val addresses = mutableListOf<InetAddress>()
        for (type in listOf("A" to 1, "AAAA" to 28)) {
            for (provider in providers) {
                queryDoh(provider, hostname, type.first, type.second)?.let { addresses.addAll(it) }
                if (addresses.isNotEmpty()) break
            }
        }
        return addresses.distinct().takeIf { it.isNotEmpty() }
    }

    private fun queryDoh(
        provider: DohProvider,
        hostname: String,
        typeName: String,
        typeNumber: Int
    ): List<InetAddress>? {
        val url = provider.baseUrl + "?name=" +
            URLEncoder.encode(hostname, "UTF-8") + "&type=" + typeName
        val requestBuilder = Request.Builder().url(url)
        if (provider.requiresJsonHeader) {
            requestBuilder.header("Accept", "application/dns-json")
        }
        return try {
            dohClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val answer = json.optJSONArray("Answer") ?: return null
                val found = mutableListOf<InetAddress>()
                for (i in 0 until answer.length()) {
                    val item = answer.getJSONObject(i)
                    if (item.optInt("type", -1) != typeNumber) continue
                    val data = if (item.has("data") && !item.isNull("data")) item.getString("data") else null
                        ?: continue
                    try {
                        found.add(InetAddress.getByName(data))
                    } catch (_: Exception) {
                        // resposta malformada — ignora e segue
                    }
                }
                found.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            // DoH indisponível (sem rede, DNS do provedor bloqueado, etc.)
            null
        }
    }
}
