package com.pokewallet.network

import com.pokewallet.crypto.Network
import java.net.Proxy

/**
 * [ChainDataSource] que fala com Blockstream/mempool.space (mesmos
 * provedores de [BlockstreamClient], mesmo fallback entre eles) sempre
 * roteado por um proxy SOCKS (Tor via Orbot local) — mesmo raciocínio já
 * usado no broadcast ([BlockstreamClient.broadcastViaProxy]), agora
 * disponível pra QUALQUER leitura, não só transmissão de tx.
 *
 * Escopo desta sessão: usado só na confirmação de UTXOs Silent Payments
 * (ver `WalletViewModel.syncSilentPayments`), quando o usuário liga
 * explicitamente "via Tor" na Mochila — NÃO é o dataSource padrão do
 * resto do app (saldo normal/scan continuam via [NodePrefs.dataSource],
 * decisão deliberada pra não fazer o app inteiro depender do Orbot rodando
 * pra mostrar saldo). Motivo de existir: mempool.space/Blockstream já
 * bateram rate limit por IP em campo neste projeto (ver
 * [BlockstreamClient.baseUrls]) — um scan de Silent Payments faz uma
 * chamada de rede por UTXO candidato confirmado, e Tor troca o IP de saída
 * a cada circuito, evitando que o MESMO IP do aparelho acumule todo o
 * tráfego perante o provedor.
 */
class TorBlockstreamDataSource(private val proxy: Proxy) : ChainDataSource {
    override fun getAddressStats(address: String, network: Network): AddressStats =
        BlockstreamClient.getAddressStatsViaProxy(address, network, proxy)

    override fun getUtxos(address: String, network: Network): List<RemoteUtxo> =
        BlockstreamClient.getUtxosViaProxy(address, network, proxy)

    override fun getFeeEstimates(network: Network): FeeEstimates =
        BlockstreamClient.getFeeEstimatesViaProxy(network, proxy)

    override fun broadcast(rawHex: String, network: Network): String =
        BlockstreamClient.broadcastViaProxy(rawHex, network, proxy)

    override fun getRawTx(txid: String, network: Network): String =
        BlockstreamClient.getRawTxViaProxy(txid, network, proxy)

    override fun getTipHeight(network: Network): Long =
        BlockstreamClient.getTipHeightViaProxy(network, proxy)
}
