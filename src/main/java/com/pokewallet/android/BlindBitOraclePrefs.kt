package com.pokewallet.android

import android.content.Context
import com.pokewallet.crypto.Network

/**
 * Configuração do host do blindbit-oracle (scan de recebimento Silent
 * Payments, Fase 3) — mesmo padrão de [NodePrefs]: um valor customizado
 * opcional, com um bootstrap público como padrão.
 *
 * Hosts padrão confirmados direto do código-fonte do `blindbit-desktop`
 * (`internal/configs/defaults.go`) e testados ao vivo (ALPN "h2" na porta
 * 443) durante a implementação. Só existem instâncias públicas oficiais
 * pra mainnet e signet — não há uma pra testnet3 "de verdade" nem, por
 * natureza, pra regtest (rede local, ninguém indexa isso publicamente).
 *
 * Esta wallet não distingue testnet3 de signet no enum [Network] (os dois
 * usam o mesmo valor TESTNET — mesmo HRP "tb", mesmo coinType — o usuário
 * escolhe a rede de fato só pelo servidor Electrum/Blockstream que
 * configura). Como todo o plano de testes de Silent Payments deste
 * projeto usa signet (não testnet3), o padrão de TESTNET aqui aponta pro
 * oracle de signet — quem estiver de fato em testnet3 precisa apontar pra
 * um oracle próprio manualmente (o scan simplesmente não vai achar nada
 * de útil contra o servidor de signet, sem risco de dado errado: alturas
 * e hashes de bloco são de blockchains completamente diferentes).
 */
object BlindBitOraclePrefs {

    private const val PREFS_NAME       = "pokewallet_prefs"
    private const val KEY_TLS          = "blindbit_oracle_tls"
    private const val KEY_CONFIRM_TOR  = "blindbit_confirm_via_tor"
    private const val KEY_ELECTRUM_FALLBACK = "blindbit_electrum_fallback"

    const val DEFAULT_HOST_MAINNET = "oracle.setor.dev"
    const val DEFAULT_HOST_SIGNET  = "signet.oracle.setor.dev"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Chave do host CUSTOMIZADO, uma POR REDE — bug real encontrado ao vivo
     * nesta sessão (usuário testando em signet, depois indo pra mainnet
     * com fundos reais): a versão anterior usava uma chave ÚNICA
     * compartilhada entre redes, então só ABRIR o diálogo "Configurar
     * Oracle" em signet e tocar "Salvar" (mesmo sem editar nada) fixava
     * "signet.oracle.setor.dev" como host CUSTOMIZADO — que passava a
     * vazar pro mainnet depois, porque [host] checava o customizado ANTES
     * do padrão por rede. Scan de mainnet contra o oracle de signet não dá
     * erro claro nenhum sozinho (por isso a checagem de rede em
     * [com.pokewallet.network.SilentPaymentsSync] logo abaixo) — só nunca
     * acha o pagamento real, silenciosamente.
     */
    private fun keyHostFor(network: Network) = "blindbit_oracle_host_${network.name}"

    /** Host padrão pra rede — null pra REGTEST (sem instância pública
     *  possível; precisa de host customizado, ex.: oracle próprio na LAN). */
    fun defaultHostFor(network: Network): String? = when (network) {
        Network.MAINNET -> DEFAULT_HOST_MAINNET
        Network.TESTNET -> DEFAULT_HOST_SIGNET
        Network.REGTEST -> null
    }

    /** Host customizado salvo PRA ESSA REDE, se houver; senão o padrão da
     *  rede (pode ser null em REGTEST sem host customizado). */
    fun host(context: Context, network: Network): String? =
        prefs(context).getString(keyHostFor(network), null)?.takeIf { it.isNotBlank() } ?: defaultHostFor(network)

    /** Ligado por padrão — desligar é escolha explícita do usuário pra
     *  um oracle próprio sem certificado (mesmo raciocínio de
     *  [NodePrefs.isTlsEnabled], mas invertido: aqui o padrão é seguro
     *  porque o bootstrap público real está na internet aberta, não numa
     *  LAN confiável). */
    fun isTlsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TLS, true)

    fun save(context: Context, network: Network, host: String?, useTls: Boolean) {
        prefs(context).edit()
            .putString(keyHostFor(network), host?.trim()?.takeIf { it.isNotBlank() })
            .putBoolean(KEY_TLS, useTls)
            .apply()
    }

    /** Texto curto pra UI (Mochila, seção "Silent Payments") — não inclui
     *  altura escaneada/contagem de UTXOs, isso é responsabilidade de quem
     *  chama (depende da carteira ativa, que este objeto não conhece). */
    fun statusLabel(context: Context, network: Network): String {
        val h = host(context, network) ?: return "⚠️ Nenhum oracle configurado pra esta rede"
        return "🔒 Oracle: $h" + if (!isTlsEnabled(context)) " (sem TLS)" else ""
    }

    /** URL completa pronta pra [com.pokewallet.network.BlindBitOracleClient]
     *  — null se não há host (customizado nem padrão) pra essa rede. */
    fun baseUrl(context: Context, network: Network): String? {
        val h = host(context, network) ?: return null
        val scheme = if (isTlsEnabled(context)) "https" else "http"
        return "$scheme://$h"
    }

    /** Desligado por padrão (mesmo raciocínio de [TorPrefs]: sem Orbot
     *  rodando, ligar isso só trocaria "sem resultado" por "erro de
     *  conexão recusada"). Quando ligado, a CONFIRMAÇÃO de UTXOs Silent
     *  Payments (busca da tx bruta por txid, ver
     *  [com.pokewallet.network.SilentPaymentsConfirmer]) passa a usar
     *  Blockstream/mempool.space roteado por Tor (ver
     *  [com.pokewallet.network.TorBlockstreamDataSource]) em vez do
     *  [NodePrefs.dataSource] normal — pedido explícito do usuário depois
     *  de mempool/Blockstream já terem bloqueado o IP do app em campo
     *  antes (ver histórico em [com.pokewallet.network.BlockstreamClient.baseUrls]).
     *  Escopo deliberadamente restrito à confirmação SP: saldo/scan normal
     *  do resto do app continuam sem depender do Orbot. */
    fun isConfirmViaTorEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIRM_TOR, false)

    fun setConfirmViaTorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_TOR, enabled).apply()
    }

    /** Fallback pra pool de Electrum públicos na confirmação de Silent
     *  Payments (ver FailoverChainDataSource). Ativado por padrão: os erros
     *  de campo ("unable to resolve host" do blockstream/mempool) deixavam o
     *  sync sem alternativa nenhuma — eram os ÚNICOS servidores consultados.
     *  Só vale em MAINNET e NUNCA junto de [isConfirmViaTorEnabled] (o
     *  fallback é clearnet) — o ViewModel aplica essas regras. */
    fun isElectrumFallbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ELECTRUM_FALLBACK, true)

    fun setElectrumFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ELECTRUM_FALLBACK, enabled).apply()
    }
}
