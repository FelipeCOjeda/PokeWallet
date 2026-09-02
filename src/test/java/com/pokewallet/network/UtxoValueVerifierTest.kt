package com.pokewallet.network

import com.pokewallet.crypto.Network
import com.pokewallet.crypto.TxIn
import com.pokewallet.crypto.TxOut
import com.pokewallet.crypto.UnsignedTransaction
import com.pokewallet.crypto.serializeLegacy
import com.pokewallet.crypto.toHex
import com.pokewallet.crypto.txid
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Cobre o cenário central da correção do achado CRÍTICO da auditoria
 * (item 1): um [ChainDataSource] que reporta um valor de UTXO diferente
 * do real (fee-inflation attack) tem que ser rejeitado ANTES de qualquer
 * assinatura, não silenciosamente aceito.
 */
class UtxoValueVerifierTest {

    private fun fakeTxid(byte: Int) = ByteArray(32) { byte.toByte() }
    private fun fakeScript(byte: Int) = ByteArray(22) { byte.toByte() }

    private val realPrevTx = UnsignedTransaction(
        version = 2,
        inputs = listOf(TxIn(fakeTxid(0x01), 0, byteArrayOf(), 0xFFFFFFFFL)),
        outputs = listOf(TxOut(value = 100_000L, scriptPubKey = fakeScript(0x02))),
        lockTime = 0L
    )

    private class FakeDataSource(private val rawTxByTxid: Map<String, String>) : ChainDataSource {
        override fun getAddressStats(address: String, network: Network) = error("não usado neste teste")
        override fun getUtxos(address: String, network: Network) = error("não usado neste teste")
        override fun getFeeEstimates(network: Network) = error("não usado neste teste")
        override fun broadcast(rawHex: String, network: Network) = error("não usado neste teste")
        override fun getTipHeight(network: Network) = error("não usado neste teste")
        override fun getRawTx(txid: String, network: Network): String =
            rawTxByTxid[txid] ?: error("txid não conhecido pelo fake: $txid")
    }

    @Test
    fun `aceita UTXO cujo valor reportado bate com a transacao real`() {
        val realTxid = realPrevTx.txid()
        val dataSource = FakeDataSource(mapOf(realTxid to realPrevTx.serializeLegacy().toHex()))

        val utxo = RemoteUtxo(txid = realTxid, vout = 0, valueSats = 100_000L, confirmed = true, blockHeight = 800_000)

        // Não deve lançar.
        UtxoValueVerifier.verify(dataSource, Network.MAINNET, utxo)
    }

    @Test
    fun `rejeita UTXO com valor mentido menor que o real - ataque de fee inflado`() {
        val realTxid = realPrevTx.txid()
        val dataSource = FakeDataSource(mapOf(realTxid to realPrevTx.serializeLegacy().toHex()))

        // Servidor malicioso reporta 40_000 sat, mas a tx real na rede tem 100_000.
        val utxoComValorMentido = RemoteUtxo(txid = realTxid, vout = 0, valueSats = 40_000L, confirmed = true, blockHeight = 800_000)

        assertThrows(UtxoValueVerifier.UtxoMismatchException::class.java) {
            UtxoValueVerifier.verify(dataSource, Network.MAINNET, utxoComValorMentido)
        }
    }

    @Test
    fun `rejeita quando a raw tx devolvida nao bate com o txid pedido`() {
        val realTxid = realPrevTx.txid()
        // dataSource devolve uma tx totalmente diferente da que tem esse txid.
        val outraTx = realPrevTx.copy(outputs = listOf(TxOut(999_999L, fakeScript(0x09))))
        val dataSource = FakeDataSource(mapOf(realTxid to outraTx.serializeLegacy().toHex()))

        val utxo = RemoteUtxo(txid = realTxid, vout = 0, valueSats = 999_999L, confirmed = true, blockHeight = 800_000)

        assertThrows(UtxoValueVerifier.UtxoMismatchException::class.java) {
            UtxoValueVerifier.verify(dataSource, Network.MAINNET, utxo)
        }
    }

    @Test
    fun `rejeita vout fora do range de saidas da transacao`() {
        val realTxid = realPrevTx.txid()
        val dataSource = FakeDataSource(mapOf(realTxid to realPrevTx.serializeLegacy().toHex()))

        val utxo = RemoteUtxo(txid = realTxid, vout = 5, valueSats = 100_000L, confirmed = true, blockHeight = 800_000)

        assertThrows(UtxoValueVerifier.UtxoMismatchException::class.java) {
            UtxoValueVerifier.verify(dataSource, Network.MAINNET, utxo)
        }
    }
}
