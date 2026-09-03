package com.pokewallet.crypto

import com.pokewallet.network.BlindBitOracleClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testa o scanner com dados SINTÉTICOS, mas construídos com o próprio
 * [Bip352] (já validado contra os vetores oficiais em Bip352Test) do lado
 * REMETENTE — monta um output SP de verdade, empacota como se fosse a
 * resposta do blindbit-oracle, e confere que o scanner (lado
 * DESTINATÁRIO) acha exatamente o que o remetente mandou. Não usa rede.
 */
class SilentPaymentsScannerTest {

    // Chaves arbitrárias (não são segredo de produção, só fixtures de teste).
    private val input1Priv = "eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1".hexToBytes()
    private val input2Priv = "93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16".hexToBytes()
    private val scanPriv   = "0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c".hexToBytes()
    private val spendPriv  = "1d37787c2b7116ee983e9f9c13269df29091b391c04db94239e0d2bc2182c3bf".hexToBytes()

    private val scanPub  = Secp256k1.publicKeyFromPrivate(scanPriv)
    private val spendPub = Secp256k1.publicKeyFromPrivate(spendPriv)

    private val outpoints = listOf(
        Bip352.outpoint("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16".hexToBytes().reversedArray(), 0),
        Bip352.outpoint("a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d".hexToBytes().reversedArray(), 0)
    )
    private val inputs = listOf(
        Bip352.SenderInput(input1Priv, isTaproot = false),
        Bip352.SenderInput(input2Priv, isTaproot = false)
    )

    /** tweak = input_hash·A, exatamente o que o blindbit-oracle manda (ver
     *  documentação de BlindBitOracleClient/GRPC.md). */
    private fun oracleTweak(): ByteArray {
        val a = Bip352.sumSenderInputKeys(inputs)
        val bigA = Secp256k1.multiplyGenerator(a)
        val outpointL = Bip352.smallestOutpoint(outpoints)
        val h = Bip352.inputHash(outpointL, bigA)
        return Secp256k1.pointMultiply(bigA, h)
    }

    private fun senderSharedSecret(): ByteArray {
        val a = Bip352.sumSenderInputKeys(inputs)
        return Bip352.senderSharedSecret(a, Bip352.smallestOutpoint(outpoints), scanPub)
    }

    private fun outputXOnlyFor(k: Int): ByteArray =
        Bip352.outputPublicKey(spendPub, senderSharedSecret(), k).copyOfRange(1, 33)

    private val fakeTxid = ByteArray(32) { 0x42 }
    private val unrelatedDecoyShort = ByteArray(8) { 0xEE.toByte() }

    @Test
    fun `acha o output real quando o short bate`() {
        val ourOutput = outputXOnlyFor(0)
        val block = BlindBitOracleClient.BlockScanData(
            blockHashLE = ByteArray(32),
            blockHeight = 900_000L,
            txs = listOf(
                BlindBitOracleClient.TxTweakItem(
                    txidLE       = fakeTxid,
                    tweak        = oracleTweak(),
                    outputsShort = listOf(unrelatedDecoyShort, ourOutput.copyOfRange(0, 8))
                )
            ),
            spentOutputsShort = emptyList()
        )

        val found = SilentPaymentsScanner.scanBlock(block, scanPriv, spendPub)

        assertEquals(1, found.size)
        assertEquals(0, found[0].k)
        assertEquals(ourOutput.toHex(), found[0].outputXOnlyPubKey.toHex())
        assertEquals(Bip352.outputTweak(senderSharedSecret(), 0).toHex(), found[0].tweak.toHex())
        assertEquals(900_000L, found[0].blockHeight)
        assertTrue(found[0].txidLE.contentEquals(fakeTxid))
    }

    @Test
    fun `nao acha nada quando o short nao bate com nenhum output nosso`() {
        val block = BlindBitOracleClient.BlockScanData(
            blockHashLE = ByteArray(32),
            blockHeight = 900_000L,
            txs = listOf(
                BlindBitOracleClient.TxTweakItem(
                    txidLE       = fakeTxid,
                    tweak        = oracleTweak(),
                    outputsShort = listOf(unrelatedDecoyShort)
                )
            ),
            spentOutputsShort = emptyList()
        )

        assertTrue(SilentPaymentsScanner.scanBlock(block, scanPriv, spendPub).isEmpty())
    }

    @Test
    fun `acha os dois outputs quando o mesmo remetente paga duas vezes na mesma tx, k crescente`() {
        val output0 = outputXOnlyFor(0)
        val output1 = outputXOnlyFor(1)
        val block = BlindBitOracleClient.BlockScanData(
            blockHashLE = ByteArray(32),
            blockHeight = 900_001L,
            txs = listOf(
                BlindBitOracleClient.TxTweakItem(
                    txidLE       = fakeTxid,
                    tweak        = oracleTweak(),
                    outputsShort = listOf(output0.copyOfRange(0, 8), output1.copyOfRange(0, 8))
                )
            ),
            spentOutputsShort = emptyList()
        )

        val found = SilentPaymentsScanner.scanBlock(block, scanPriv, spendPub)

        assertEquals(2, found.size)
        assertEquals(listOf(0, 1), found.map { it.k })
        assertEquals(listOf(output0.toHex(), output1.toHex()), found.map { it.outputXOnlyPubKey.toHex() })
    }

    @Test
    fun `para no limite de seguranca em vez de rodar pra sempre quando todo k bate`() {
        // Constrói outputsShort com o short REAL de k=0..1099 — força o loop
        // a "bater" em toda iteração, provando que MAX_K_PER_TX (1000) para
        // o loop em vez dele rodar pra sempre (achado real: sync em mainnet
        // travou de verdade porque o while(true) antigo não tinha limite,
        // e por ser CPU puro nenhum timeout de coroutine conseguia
        // interromper).
        val shorts = (0..1099).map { outputXOnlyFor(it).copyOfRange(0, 8) }
        val block = BlindBitOracleClient.BlockScanData(
            blockHashLE = ByteArray(32),
            blockHeight = 900_002L,
            txs = listOf(
                BlindBitOracleClient.TxTweakItem(
                    txidLE       = fakeTxid,
                    tweak        = oracleTweak(),
                    outputsShort = shorts
                )
            ),
            spentOutputsShort = emptyList()
        )

        val found = SilentPaymentsScanner.scanBlock(block, scanPriv, spendPub)

        assertEquals(1000, found.size)
        assertEquals((0..999).toList(), found.map { it.k })
    }
}
