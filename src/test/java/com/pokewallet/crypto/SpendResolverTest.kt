package com.pokewallet.crypto

import com.pokewallet.network.RemoteUtxo
import com.pokewallet.network.WalletScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Achado ALTO 11/12 da auditoria: a decisão de QUAIS UTXOs entram numa tx
 * (WalletViewModel.resolveSpend, antes desta extração) nunca tinha teste —
 * um bug aqui gasta o UTXO errado ou ignora um congelado.
 */
class SpendResolverTest {

    private fun utxo(txid: String, vout: Int, value: Long) =
        RemoteUtxo(txid = txid, vout = vout, valueSats = value, confirmed = true, blockHeight = 800_000)

    private fun scannedAddress(chain: Int, index: Int, utxos: List<RemoteUtxo>): WalletScanner.ScannedAddress {
        val address = "addr-$chain-$index"
        val totalSats = utxos.sumOf { it.valueSats }
        return WalletScanner.ScannedAddress(
            chain = chain, index = index, address = address,
            stats = com.pokewallet.network.AddressStats(
                address = address, confirmedSats = totalSats, pendingSats = 0L,
                txCount = utxos.size, mempoolTxCount = 0
            ),
            utxos = utxos
        )
    }

    // ── candidatesFrom: filtro de congelados ──

    @Test
    fun `candidatesFrom remove UTXOs congelados e mantem o resto`() {
        val u1 = utxo("aa".repeat(32).take(64), 0, 10_000L)
        val u2 = utxo("bb".repeat(32).take(64), 1, 20_000L)
        val addresses = listOf(scannedAddress(0, 0, listOf(u1, u2)))
        val frozen = setOf("${u1.txid}:${u1.vout}")

        val result = SpendResolver.candidatesFrom(addresses, frozen)

        assertEquals(1, result.size)
        assertEquals(u2.txid, result[0].utxo.txid)
    }

    // ── chooseUtxos: manual ──

    @Test
    fun `chooseUtxos manual usa EXATAMENTE os UTXOs pedidos, nunca completa com outros`() {
        val u1 = utxo("11".repeat(32), 0, 10_000L)
        val u2 = utxo("22".repeat(32), 0, 90_000L)
        val candidates = listOf(
            SpendResolver.Candidate(0, 0, u1),
            SpendResolver.Candidate(0, 1, u2)
        )

        val chosen = SpendResolver.chooseUtxos(
            candidates, amountSats = 5_000L, sweep = false,
            manualUtxoKeys = setOf("${u1.txid}:${u1.vout}"),
            feeRateSatPerVbyte = 1.0, spendType = SpendType.BIP84
        )

        assertEquals(1, chosen.size)
        assertEquals(u1.txid, chosen[0].utxo.txid)
    }

    @Test
    fun `chooseUtxos manual falha se o UTXO pedido nao esta mais disponivel`() {
        val candidates = listOf(SpendResolver.Candidate(0, 0, utxo("33".repeat(32), 0, 10_000L)))

        assertThrows(IllegalStateException::class.java) {
            SpendResolver.chooseUtxos(
                candidates, amountSats = 1_000L, sweep = false,
                manualUtxoKeys = setOf("nao-existe:0"),
                feeRateSatPerVbyte = 1.0, spendType = SpendType.BIP84
            )
        }
    }

    @Test
    fun `chooseUtxos manual falha se o valor selecionado nao cobre valor mais taxa`() {
        val candidates = listOf(SpendResolver.Candidate(0, 0, utxo("44".repeat(32), 0, 1_000L)))

        assertThrows(IllegalArgumentException::class.java) {
            SpendResolver.chooseUtxos(
                candidates, amountSats = 999_000L, sweep = false,
                manualUtxoKeys = setOf("${candidates[0].utxo.txid}:0"),
                feeRateSatPerVbyte = 1.0, spendType = SpendType.BIP84
            )
        }
    }

    // ── chooseUtxos: sweep ──

    @Test
    fun `chooseUtxos sweep pega TODOS os candidatos (ja filtrados de congelados por candidatesFrom)`() {
        val candidates = listOf(
            SpendResolver.Candidate(0, 0, utxo("55".repeat(32), 0, 10_000L)),
            SpendResolver.Candidate(1, 3, utxo("66".repeat(32), 0, 20_000L))
        )

        val chosen = SpendResolver.chooseUtxos(candidates, amountSats = null, sweep = true, manualUtxoKeys = null, feeRateSatPerVbyte = 1.0, spendType = SpendType.BIP84)

        assertEquals(2, chosen.size)
    }

    // ── chooseUtxos: automático ──

    @Test
    fun `chooseUtxos automatico nao inclui UTXO desnecessario quando um so ja cobre o valor`() {
        val small = utxo("77".repeat(32), 0, 5_000L)
        val big   = utxo("88".repeat(32), 0, 100_000L)
        val candidates = listOf(SpendResolver.Candidate(0, 0, small), SpendResolver.Candidate(0, 1, big))

        val chosen = SpendResolver.chooseUtxos(candidates, amountSats = 10_000L, sweep = false, manualUtxoKeys = null, feeRateSatPerVbyte = 1.0, spendType = SpendType.BIP84)

        assertTrue("CoinSelector deveria escolher só o suficiente, não os dois UTXOs", chosen.size < candidates.size)
    }

    // ── resolve: destino + troco ──

    @Test
    fun `resolve calcula troco quando sobra valor alem do destino e da taxa`() {
        val candidates = listOf(SpendResolver.Candidate(0, 0, utxo("99".repeat(32), 0, 100_000L)))
        val destination = AddressBuilder.p2wpkh(Hashes.hash160("dest".toByteArray()), Network.TESTNET)

        val resolved = SpendResolver.resolve(candidates, destination, Network.TESTNET, amountSats = 10_000L, sweep = false, feeRateSatPerVbyte = 1.0, spendType = SpendType.BIP84)

        assertEquals(10_000L, resolved.sendAmount)
        assertTrue("deveria sobrar troco (100_000 de input, 10_000 de destino, taxa pequena)", (resolved.changeValue ?: 0L) > 0L)
    }

    @Test
    fun `resolve sem troco quando sweep gasta tudo`() {
        val candidates = listOf(SpendResolver.Candidate(0, 0, utxo("aa".repeat(32), 0, 50_000L)))
        val destination = AddressBuilder.p2wpkh(Hashes.hash160("dest2".toByteArray()), Network.TESTNET)

        val resolved = SpendResolver.resolve(candidates, destination, Network.TESTNET, amountSats = null, sweep = true, feeRateSatPerVbyte = 1.0, spendType = SpendType.BIP84)

        assertEquals(null, resolved.changeValue)
        assertTrue("valor enviado no sweep deve ser menor que o total de input (desconta taxa)", resolved.sendAmount < 50_000L)
    }
}
