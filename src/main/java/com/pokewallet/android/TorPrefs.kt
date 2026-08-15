package com.pokewallet.android

import android.content.Context
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Configuração do proxy SOCKS5 do Orbot (https://guardianproject.info/apps/org.torproject.android/)
 * — a wallet não embute Tor, só sabe falar SOCKS5 com um proxy local já
 * rodando. Usado só no passo de BROADCAST (ver [SendMode.Tor] em
 * WalletViewModel) — a wallet não implementa um cliente Tor completo, só
 * depende do Orbot já estar instalado e com o proxy SOCKS ligado.
 * Desligado por padrão: sem o Orbot rodando, tentar broadcast via Tor só
 * falharia com "conexão recusada".
 */
object TorPrefs {

    private const val PREFS_NAME = "pokewallet_prefs"
    private const val KEY_HOST   = "tor_socks_host"
    private const val KEY_PORT   = "tor_socks_port"
    // Porta SOCKS5 padrão do Orbot (configurável nas opções do próprio app).
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 9050

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getHost(context: Context): String =
        prefs(context).getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST

    fun getPort(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun save(context: Context, host: String, port: Int) {
        prefs(context).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .apply()
    }

    fun proxy(context: Context): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(getHost(context), getPort(context)))

    fun statusLabel(context: Context): String =
        "🧅 Proxy SOCKS: ${getHost(context)}:${getPort(context)} (usado só ao escolher \"Tor\" no envio)"
}
