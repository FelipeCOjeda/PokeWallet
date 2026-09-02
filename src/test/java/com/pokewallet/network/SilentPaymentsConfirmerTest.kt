package com.pokewallet.network

import com.pokewallet.crypto.Network
import com.pokewallet.crypto.SilentPaymentsScanner
import com.pokewallet.crypto.TxIn
import com.pokewallet.crypto.TxOut
import com.pokewallet.crypto.UnsignedTransaction
import com.pokewallet.crypto.hexToBytes
import com.pokewallet.crypto.serializeLegacy
import com.pokewallet.crypto.toHex
import com.pokewallet.crypto.txid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SilentPaymentsConfirmer] busca a tx bruta via [ChainDataSource] e
 * confere os 32 bytes reais on-chain — aqui com uma tx sintética montada
 * com o serializer já testado do próprio app ([UnsignedTransaction]), num
 * [ChainDataSource] fake em memória (mesmo espírito de ElectrumClientTest:
 * sem rede real na suíte).
 */
class SilentPaymentsConfirmerTest {

    private val network = Network.TESTNET

    private class FakeDataSource(private val txsByTxid: Map<String, String>) : ChainDataSource {
        override fun getAddressStats(address: String, network: Network) = throw UnsupportedOperationException()
        override fun getUtxos(address: String, network: Network) = throw UnsupportedOperationException()
        override fun getFeeEstimates(network: Network) = throw UnsupportedOperationException()
        override fun broadcast(rawHex: String, network: Network) = throw UnsupportedOperationException()
        override fun getRawTx(txid: String, network: Network) = txsByTxid[txid] ?: error("txid não encontrado no fake: $txid")
    }

    @Test
    fun `confirma um candidato real, achando o vout certo pelo scriptPubKey`() {
        val ourXOnly = ByteArray(32) { it.toByte() }
        val ourScript = byteArrayOf(0x51, 0x20) + ourXOnly
        val otherScript = byteArrayOf(0x51, 0x20) + ByteArray(32) { 0x99.toByte() }

        val unsignedTx = UnsignedTransaction(
            version = 2,
            inputs = listOf(TxIn(prevTxId = ByteArray(32) { 0x11 }, prevIndex = 0, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)),
            outputs = listOf(TxOut(50_000L, otherScript), TxOut(123_456L, ourScript)),
            lockTime = 0L
        )
        val rawTxHex = unsignedTx.serializeLegacy().toHex()
        val txidDisplay = unsignedTx.txid()
        val dataSource = FakeDataSource(mapOf(txidDisplay to rawTxHex))

        val candidate = SilentPaymentsScanner.Candidate(
            txidLE            = txidDisplay.hexToBytes().reversedArray(),
            outputXOnlyPubKey = ourXOnly,
            k                 = 0,
            tweak             = ByteArray(32) { 0x07 },
            blockHeight       = 123L
        )

        val confirmed = SilentPaymentsConfirmer.confirm(candidate, dataSource, network)

        assertNotNull(confirmed)
        assertEquals(txidDisplay, confirmed!!.txid)
        assertEquals(1, confirmed.vout)
        assertEquals(123_456L, confirmed.valueSats)
        assertEquals(ourXOnly.toHex(), confirmed.outputXOnlyPubKey.toHex())
        assertEquals(0, confirmed.k)
        assertEquals(123L, confirmed.blockHeight)
    }

    @Test
    fun `retorna null quando nenhum output bate - falso positivo do filtro de 8 bytes`() {
        val unsignedTx = UnsignedTransaction(
            version = 2,
            inputs = listOf(TxIn(prevTxId = ByteArray(32) { 0x11 }, prevIndex = 0, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)),
            outputs = listOf(TxOut(50_000L, byteArrayOf(0x51, 0x20) + ByteArray(32) { 0x99.toByte() })),
            lockTime = 0L
        )
        val rawTxHex = unsignedTx.serializeLegacy().toHex()
        val txidDisplay = unsignedTx.txid()
        val dataSource = FakeDataSource(mapOf(txidDisplay to rawTxHex))

        val candidate = SilentPaymentsScanner.Candidate(
            txidLE            = txidDisplay.hexToBytes().reversedArray(),
            outputXOnlyPubKey = ByteArray(32) { 0x42 },
            k                 = 0,
            tweak             = ByteArray(32),
            blockHeight       = 1L
        )

        assertNull(SilentPaymentsConfirmer.confirm(candidate, dataSource, network))
    }
}
