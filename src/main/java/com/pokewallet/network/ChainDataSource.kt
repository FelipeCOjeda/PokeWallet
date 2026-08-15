package com.pokewallet.network

import com.pokewallet.crypto.Network

/**
 * Fonte de dados de blockchain (saldo/UTXOs/taxas/broadcast) — abstrai de
 * ONDE os dados vêm. [BlockstreamClient] implementa via API Esplora
 * pública (internet); [ElectrumClient] implementa falando com um node
 * próprio (ex: florestad) via protocolo Electrum, sem depender de
 * terceiro. [WalletScanner] recebe uma instância e não sabe qual é.
 */
interface ChainDataSource {
    fun getAddressStats(address: String, network: Network): AddressStats
    fun getUtxos(address: String, network: Network): List<RemoteUtxo>
    fun getFeeEstimates(network: Network): FeeEstimates
    fun broadcast(rawHex: String, network: Network): String

    /**
     * Transação bruta (hex) identificada por [txid] — usada por
     * [UtxoValueVerifier] pra conferir, ANTES de assinar, que o valor de um
     * UTXO reportado por este provedor bate com a transação real que o
     * criou (o provedor não consegue forjar isso sem quebrar SHA-256, já
     * que o txid é o hash da própria tx).
     */
    fun getRawTx(txid: String, network: Network): String
}
