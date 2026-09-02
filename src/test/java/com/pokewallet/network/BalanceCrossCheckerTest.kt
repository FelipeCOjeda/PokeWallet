package com.pokewallet.network

import com.pokewallet.crypto.Network
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cobre a correção do achado MÉDIO da auditoria (item 13): sem essa
 * checagem opcional, um node Electrum malicioso podia reportar saldo
 * menor do que o real pra endereços que ele já revelou como ativos, e a
 * wallet nunca teria como saber (limitação documentada em
 * BalanceCrossChecker: não pega omissão de endereço INTEIRO, só valor
 * subestimado num endereço já conhecido).
 */
class BalanceCrossCheckerTest {

    private class FakeDataSource(private val statsByAddress: Map<String, AddressStats>) : ChainDataSource {
        override fun getAddressStats(address: String, network: Network) = statsByAddress.getValue(address)
        override fun getUtxos(address: String, network: Network) = error("não usado neste teste")
        override fun getFeeEstimates(network: Network) = error("não usado neste teste")
        override fun broadcast(rawHex: String, network: Network) = error("não usado neste teste")
        override fun getRawTx(txid: String, network: Network) = error("não usado neste teste")
        override fun getTipHeight(network: Network) = error("não usado neste teste")
    }

    private fun scanned(address: String, confirmedSats: Long) = WalletScanner.ScannedAddress(
        chain = 0, index = 0, address = address,
        stats = AddressStats(address, confirmedSats, 0L, 1, 0),
        utxos = emptyList()
    )

    @Test
    fun `sem divergencia quando node reporta o mesmo valor da API publica`() {
        val addr = "bc1qexemplo"
        val addresses = listOf(scanned(addr, 50_000L))
        val publicSource = FakeDataSource(mapOf(addr to AddressStats(addr, 50_000L, 0L, 1, 0)))

        val result = BalanceCrossChecker.check(addresses, Network.MAINNET, publicSource)

        assertEquals(0L, result.divergenceSats)
    }

    @Test
    fun `divergencia positiva quando node reporta MENOS que a API publica - possivel censura`() {
        val addr = "bc1qexemplo"
        // Node malicioso reporta só 10_000, mas a API pública vê 100_000 de verdade.
        val addresses = listOf(scanned(addr, 10_000L))
        val publicSource = FakeDataSource(mapOf(addr to AddressStats(addr, 100_000L, 0L, 1, 0)))

        val result = BalanceCrossChecker.check(addresses, Network.MAINNET, publicSource)

        assertEquals(90_000L, result.divergenceSats)
    }

    @Test
    fun `soma varios enderecos corretamente`() {
        val addr1 = "bc1qum"
        val addr2 = "bc1qdois"
        val addresses = listOf(scanned(addr1, 1_000L), scanned(addr2, 2_000L))
        val publicSource = FakeDataSource(
            mapOf(
                addr1 to AddressStats(addr1, 1_000L, 0L, 1, 0),
                addr2 to AddressStats(addr2, 2_500L, 0L, 1, 0)
            )
        )

        val result = BalanceCrossChecker.check(addresses, Network.MAINNET, publicSource)

        assertEquals(3_000L, result.configuredTotalSats)
        assertEquals(3_500L, result.publicTotalSats)
        assertEquals(500L, result.divergenceSats)
    }
}
