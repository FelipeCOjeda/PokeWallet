package com.pokewallet.nostr

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Diretório de geo-relays — mesma lógica usada pelo app BitChat
 * (GeoRelayDirectory.swift) e pelo bot bitchat-broadcaster
 * (src/georelay.js) pra escolher em quais relays Nostr publicar um
 * canal de geohash: os N relays com menor distância haversine até o
 * centro do geohash. Publicador e assinante só se enxergam se
 * calcularem (aproximadamente) o mesmo conjunto.
 */
object GeoRelayDirectory {

    private const val DIRECTORY_URL =
        "https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv"

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * Relays fixos usados pelo bitchat-broadcaster quando o cálculo
     * dinâmico dele falha no boot (config.js FALLBACK_RELAYS, copiado
     * literalmente). Incluídos aqui na união com o cálculo dinâmico
     * pra garantir sobreposição mesmo se o snapshot do bot estiver
     * desatualizado.
     */
    val FALLBACK_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://relay.nostr.band",
        "wss://nos.lol",
        "wss://relay.snort.social"
    )

    data class Entry(val host: String, val lat: Double, val lon: Double)

    /** Decodifica um geohash pro centro (lat, lon) da sua célula. */
    fun decodeGeohash(geohash: String): Pair<Double, Double> {
        var evenBit = true
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0

        for (c in geohash.lowercase()) {
            val idx = BASE32.indexOf(c)
            require(idx >= 0) { "caractere de geohash inválido: $c" }
            for (n in 4 downTo 0) {
                val bit = (idx shr n) and 1
                if (evenBit) {
                    val lonMid = (lonMin + lonMax) / 2
                    if (bit == 1) lonMin = lonMid else lonMax = lonMid
                } else {
                    val latMid = (latMin + latMax) / 2
                    if (bit == 1) latMin = latMid else latMax = latMid
                }
                evenBit = !evenBit
            }
        }

        return (latMin + latMax) / 2 to (lonMin + lonMax) / 2
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = (lat2 - lat1) * Math.PI / 180
        val dLon = (lon2 - lon1) * Math.PI / 180
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * Math.PI / 180) * cos(lat2 * Math.PI / 180) * sin(dLon / 2).let { it * it }
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun parseDirectoryCsv(text: String): List<Entry> =
        text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .drop(1) // header
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size < 3) return@mapNotNull null
                val host = parts[0]
                val lat = parts[1].toDoubleOrNull()
                val lon = parts[2].toDoubleOrNull()
                if (host.isEmpty() || lat == null || lon == null) null else Entry(host, lat, lon)
            }

    /**
     * Relays (wss://) mais próximos do centro do geohash, unidos com
     * os relays fallback fixos do bot. Se a busca do diretório falhar
     * (sem internet pro GitHub, etc), retorna só os fallback — nunca
     * lança exceção pro chamador.
     */
    fun closestRelays(geohash: String, count: Int = 5): List<String> {
        val dynamic = try {
            val entries = parseDirectoryCsv(get(DIRECTORY_URL))
            val (lat, lon) = decodeGeohash(geohash)
            entries
                .map { it to haversineKm(lat, lon, it.lat, it.lon) }
                .sortedWith(compareBy({ it.second }, { it.first.host }))
                .take(count)
                .map { "wss://${it.first.host}" }
        } catch (_: Exception) {
            emptyList()
        }

        return (dynamic + FALLBACK_RELAYS).distinct()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = "GET"
        return try {
            val code = conn.responseCode
            check(code in 200..299) { "geo relay directory erro $code" }
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
