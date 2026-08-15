package com.pokewallet.android

import android.content.Context
import com.pokewallet.network.BlockstreamClient
import com.pokewallet.network.ChainDataSource
import com.pokewallet.network.ElectrumClient

/**
 * Node Electrum próprio (opcional, configurável na Mochila) como fonte de
 * saldo/UTXO/taxas/broadcast, no lugar da API pública Blockstream/
 * mempool.space — reduz dependência de terceiro e funciona sem internet
 * (só a LAN até o node). Desligado = comportamento de sempre.
 */
object NodePrefs {

    private const val PREFS_NAME = "pokewallet_prefs"
    private const val KEY_ENABLED = "electrum_enabled"
    private const val KEY_HOST    = "electrum_host"
    private const val KEY_PORT    = "electrum_port"
    private const val KEY_TLS     = "electrum_tls"
    private const val KEY_CROSS_CHECK = "electrum_cross_check"
    const val DEFAULT_PORT = 50001

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun getHost(context: Context): String? = prefs(context).getString(KEY_HOST, null)
    fun getPort(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)
    // Desligado por padrão: preserva o comportamento de quem já configurou
    // um node LAN sem TLS (ex.: Floresta doméstico, sem certificado) antes
    // dessa opção existir — ligar TLS é uma escolha explícita do usuário,
    // necessária pra qualquer node fora de uma rede local confiável.
    fun isTlsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TLS, false)
    // Desligado por padrão: cruzar saldo com a API pública a cada scan
    // significa falar com Blockstream/mempool.space mesmo com node próprio
    // ligado — o oposto do motivo de ligar o node próprio pra começo de
    // conversa (privacidade/independência). Fica opt-in pra quem quer essa
    // camada extra de verificação e aceita o trade-off.
    fun isCrossCheckEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CROSS_CHECK, false)

    fun save(context: Context, enabled: Boolean, host: String, port: Int, useTls: Boolean, crossCheck: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putBoolean(KEY_TLS, useTls)
            .putBoolean(KEY_CROSS_CHECK, crossCheck)
            .apply()
        invalidateCache()
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
        invalidateCache()
    }

    fun statusLabel(context: Context): String =
        if (isEnabled(context)) "🌲 Node próprio: ${getHost(context)}:${getPort(context)}"
        else "☁️ Blockstream/mempool.space (padrão)"

    // ── Resolução da fonte de dados ativa ────────────────

    // Mantém UMA conexão Electrum viva (socket TCP reusado) enquanto a
    // config não mudar — abrir uma conexão nova a cada chamada seria caro,
    // igual ao raciocínio de keep-alive do BlockstreamClient.
    @Volatile private var cachedClient: ElectrumClient? = null
    @Volatile private var cachedHost: String? = null
    @Volatile private var cachedPort: Int = -1
    @Volatile private var cachedTls: Boolean = false

    @Synchronized
    fun dataSource(context: Context): ChainDataSource {
        if (!isEnabled(context)) return BlockstreamClient
        val host = getHost(context)?.takeIf { it.isNotBlank() } ?: return BlockstreamClient
        val port = getPort(context)
        val tls  = isTlsEnabled(context)

        val cached = cachedClient
        if (cached != null && cachedHost == host && cachedPort == port && cachedTls == tls) return cached

        cached?.close()
        val client = ElectrumClient(host, port, useTls = tls)
        cachedClient = client
        cachedHost = host
        cachedPort = port
        cachedTls = tls
        return client
    }

    @Synchronized
    private fun invalidateCache() {
        cachedClient?.close()
        cachedClient = null
        cachedHost = null
        cachedPort = -1
        cachedTls = false
    }
}
