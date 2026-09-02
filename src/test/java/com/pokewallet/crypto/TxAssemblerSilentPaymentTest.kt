package com.pokewallet.crypto

import com.pokewallet.network.RemoteUtxo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fase 1 de Silent Payments (BIP-352): [Bip352] em si já foi validado
 * contra os vetores oficiais em Bip352Test — este teste cobre só a "cola"
 * de integração ([TxAssembler.resolveSilentPaymentDestination]): derivar a
 * chave certa por (chain,index), aplicar o tweak taproot quando necessário,
 * montar outpoint no formato certo e decodificar o endereço — comparando
 * contra chamar [Bip352] direto com as mesmas chaves derivadas manualmente.
 */
class TxAssemblerSilentPaymentTest {

    private val seed = "00".repeat(64).hexToBytes() // seed fixa determinística, só pra teste
    private val network = Network.TESTNET
    private val scanPubKey = "0220bcfac5b99e04ad1a06ddfb016ee13582609d60b6291e98d01a9bc9a16c96d4".hexToBytes()
    private val spendPubKey = "025cc9856d6f8375350e123978daac200c260cb5b5ae83106cab90484dcd8fcf36".hexToBytes()
    private val destination = SilentPaymentAddress.encode(scanPubKey, spendPubKey, Network.TESTNET)

    private fun utxo(txid: String, vout: Int, value: Long) =
        RemoteUtxo(txid = txid, vout = vout, valueSats = value, confirmed = true, blockHeight = 800_000)

    private fun outpointOf(u: RemoteUtxo) = Bip352.outpoint(u.txid.hexToBytes().reversedArray(), u.vout)

    @Test
    fun `resolveSilentPaymentDestination bate com Bip352 direto - SegWit v0, dois inputs`() {
        val u1 = utxo("11".repeat(32), 0, 50_000L)
        val u2 = utxo("22".repeat(32), 1, 30_000L)
        val chosen = listOf(
            SpendResolver.Candidate(0, 0, u1),
            SpendResolver.Candidate(0, 3, u2)
        )

        val actual = TxAssembler.resolveSilentPaymentDestination(chosen, destination, seed, network, SpendType.BIP84)

        val priv0 = TxAssembler.deriveKeyAndScript(seed, network, SpendType.BIP84, 0, 0).first
        val priv3 = TxAssembler.deriveKeyAndScript(seed, network, SpendType.BIP84, 0, 3).first
        val expected = Bip352.deriveSenderOutputScript(
            inputs      = listOf(Bip352.SenderInput(priv0, isTaproot = false), Bip352.SenderInput(priv3, isTaproot = false)),
            outpoints   = listOf(outpointOf(u1), outpointOf(u2)),
            scanPubKey  = scanPubKey,
            spendPubKey = spendPubKey
        )

        assertEquals(expected.toHex(), actual.toHex())
    }

    @Test
    fun `resolveSilentPaymentDestination bate com Bip352 direto - Taproot com tweak`() {
        val u1 = utxo("33".repeat(32), 0, 70_000L)
        val chosen = listOf(SpendResolver.Candidate(0, 1, u1))

        val actual = TxAssembler.resolveSilentPaymentDestination(chosen, destination, seed, network, SpendType.BIP86)

        val rawPriv1 = TxAssembler.deriveKeyAndScript(seed, network, SpendType.BIP86, 0, 1).first
        val tweaked = Secp256k1.taprootTweakPrivateKey(rawPriv1)
        val expected = Bip352.deriveSenderOutputScript(
            inputs      = listOf(Bip352.SenderInput(tweaked, isTaproot = true)),
            outpoints   = listOf(outpointOf(u1)),
            scanPubKey  = scanPubKey,
            spendPubKey = spendPubKey
        )

        assertEquals(expected.toHex(), actual.toHex())
    }

    @Test
    fun `soma um termo por INPUT mesmo com dois UTXOs no mesmo endereco, sem deduplicar`() {
        // Conferido direto no texto do BIP-352: a = a1 + a2 + ... + an, um
        // termo por input elegível — NÃO deduplica por endereço repetido.
        val u1 = utxo("44".repeat(32), 0, 10_000L)
        val u2 = utxo("44".repeat(32), 1, 20_000L)
        val chosen = listOf(
            SpendResolver.Candidate(0, 0, u1),
            SpendResolver.Candidate(0, 0, u2)
        )

        val actual = TxAssembler.resolveSilentPaymentDestination(chosen, destination, seed, network, SpendType.BIP84)

        val priv0 = TxAssembler.deriveKeyAndScript(seed, network, SpendType.BIP84, 0, 0).first
        val expected = Bip352.deriveSenderOutputScript(
            inputs      = listOf(Bip352.SenderInput(priv0, isTaproot = false), Bip352.SenderInput(priv0, isTaproot = false)),
            outpoints   = listOf(outpointOf(u1), outpointOf(u2)),
            scanPubKey  = scanPubKey,
            spendPubKey = spendPubKey
        )

        assertEquals(expected.toHex(), actual.toHex())
    }
}
