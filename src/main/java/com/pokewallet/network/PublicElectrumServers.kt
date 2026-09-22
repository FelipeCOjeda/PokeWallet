package com.pokewallet.network

/**
 * Lista curada de servidores Electrum públicos conhecidos — mesma fonte
 * oficial que Electrum/Sparrow usam por padrão (`servers.json` do projeto
 * Electrum, github.com/spesmilo/electrum/blob/master/electrum/chains/mainnet/servers.json),
 * capturada em 2026-09-21. Só entradas clearnet com TLS (porta "s" da
 * lista oficial) — sem onion (Tor já é um toggle separado, ver TorPrefs) e
 * sem nada operado pela Blockstream (mesmo provedor que já bloqueia o IP
 * por rate limit, ver comentário de 429 em WalletViewModel — incluir um
 * espelho deles aqui não adicionaria diversidade real de operador).
 *
 * Usado só como FALLBACK de [FailoverChainDataSource] pra confirmação de
 * Silent Payments (getRawTx) quando o node próprio (Floresta — Utreexo,
 * sem índice de transações históricas, não serve tx arbitrária por txid) e
 * o Blockstream/mempool.space (rate limit por IP) já falharam — nunca é a
 * fonte primária, então trocar de operador entre chamadas é aceitável.
 * Não validado individualmente (a lista de 50+ é grande o bastante pra
 * tolerar alguns servidores fora do ar sem impacto — o failover pula pro
 * próximo).
 */
object PublicElectrumServers {
    data class Entry(val host: String, val port: Int)

    val MAINNET: List<Entry> = listOf(
        Entry("104.248.139.211", 50002),
        Entry("128.0.190.26", 50002),
        Entry("142.93.6.38", 50002),
        Entry("157.245.172.236", 50002),
        Entry("167.172.42.31", 50002),
        Entry("188.230.155.0", 50002),
        Entry("2AZZARITA.hopto.org", 50002),
        Entry("2electrumx.hopto.me", 56022),
        Entry("2ex.digitaleveryware.com", 50002),
        Entry("37.205.9.165", 50002),
        Entry("5.9.83.108", 50002),
        Entry("68.183.188.105", 50002),
        Entry("73.92.198.54", 50002),
        Entry("89.248.168.53", 50002),
        Entry("alviss.coinjoined.com", 50002),
        Entry("assuredly.not.fyi", 50002),
        Entry("bitcoin.aranguren.org", 50002),
        Entry("bitcoin.lu.ke", 50002),
        Entry("bitcoins.sk", 56002),
        Entry("blackie.c3-soft.com", 57002),
        Entry("blkhub.net", 50002),
        Entry("btc.electroncash.dk", 60002),
        Entry("btc.litepay.ch", 50002),
        Entry("btc.ocf.sh", 50002),
        Entry("btce.iiiiiii.biz", 50002),
        Entry("de.poiuty.com", 50002),
        Entry("E-X.not.fyi", 50002),
        Entry("e.keff.org", 50002),
        Entry("e2.keff.org", 50002),
        Entry("eai.coincited.net", 50002),
        Entry("ecdsa.net", 110),
        Entry("electrum.bitaroo.net", 50002),
        Entry("electrum.dcn.io", 50002),
        Entry("electrum.diynodes.com", 50022),
        Entry("electrum.emzy.de", 50002),
        Entry("electrum.hodlister.co", 50002),
        Entry("electrum.hsmiths.com", 50002),
        Entry("electrum.jhoenicke.de", 50002),
        Entry("electrum.pabu.io", 50002),
        Entry("electrum.qtornado.com", 50002),
        Entry("electrum3.hodlister.co", 50002),
        Entry("electrum5.hodlister.co", 50002),
        Entry("electrumx.alexridevski.net", 50002),
        Entry("electrumx.erbium.eu", 50002),
        Entry("electrumx.schulzemic.net", 50002),
        Entry("elx.bitske.com", 50002),
        Entry("ex.btcmp.com", 50002),
        Entry("ex03.axalgo.com", 50002),
        Entry("exs.dyshek.org", 50002),
        Entry("fortress.qtornado.com", 443),
    )
}
