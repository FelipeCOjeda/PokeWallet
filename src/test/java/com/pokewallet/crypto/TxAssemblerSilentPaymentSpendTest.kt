package com.pokewallet.crypto

import com.pokewallet.network.RemoteUtxo
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Lado de GASTO de um UTXO Silent Payments (BIP-352, Fase 3) — a parte
 * mais sensível: um bug aqui (ex.: aplicar o tweak do BIP341 em cima da
 * chave já certa) faz a rede rejeitar a tx (assinatura inválida pro
 * output real), não roubar fundos, mas ainda assim fundo perdido de
 * qualquer jeito (fee paga, usuário acha que enviou).
 *
 * Monta um output SP de verdade (lado remetente, Bip352 já validado),
 * deriva a chave de gasto ([TxAssembler.deriveSilentPaymentSpendableInput]),
 * assina e finaliza uma tx de verdade
 * ([TxAssembler.signAndFinalize]) gastando ESSE UTXO, extrai a assinatura
 * do witness serializado, e verifica ela com um verificador BIP340
 * INDEPENDENTE (mesmo padrão de Secp256k1SignatureTest — não chama
 * SchnorrSigner nem Secp256k1 pra verificar, só BouncyCastle puro) contra
 * o sighash recalculado também de forma independente. Prova
 * criptográfica de ponta a ponta, não só "compilou".
 */
class TxAssemblerSilentPaymentSpendTest {

    // ── Verificador BIP340 independente (cópia do padrão já usado em
    // Secp256k1SignatureTest — cada teste de assinatura deste projeto tem
    // o seu próprio, de propósito, pra nunca ser "a função confirmando a
    // si mesma"). ──

    private val CURVE = CustomNamedCurves.getByName("secp256k1")
    private val N = CURVE.n
    private val FIELD_P = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16
    )

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun taggedHashIndependent(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray())
        return sha256(tagHash + tagHash + data)
    }

    private fun verifySchnorrIndependently(xOnlyPubKey: ByteArray, msg32: ByteArray, sig64: ByteArray): Boolean {
        val rBytes = sig64.copyOfRange(0, 32)
        val rBig = BigInteger(1, rBytes)
        val sBig = BigInteger(1, sig64.copyOfRange(32, 64))
        if (rBig >= FIELD_P || sBig >= N) return false

        val point = try {
            CURVE.curve.decodePoint(byteArrayOf(0x02) + xOnlyPubKey).normalize()
        } catch (_: Exception) {
            return false
        }

        val e = BigInteger(1, taggedHashIndependent("BIP0340/challenge", rBytes + xOnlyPubKey + msg32)).mod(N)

        val sG = CURVE.g.multiply(sBig)
        val eP = point.multiply(N.subtract(e).mod(N))
        val r = sG.add(eP).normalize()

        if (r.isInfinity) return false
        if (r.yCoord.toBigInteger().testBit(0)) return false
        return r.xCoord.toBigInteger() == rBig
    }

    /** Extrai a assinatura Schnorr (64 bytes) do witness de UM input P2TR
     *  key-path — a tx do teste sempre tem exatamente 1 input + 1 output,
     *  então o layout é fixo e simples de navegar. */
    private fun extractTapKeySig(rawTxBytes: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(rawTxBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // version
        require(buf.get() == 0x00.toByte() && buf.get() == 0x01.toByte()) { "esperava marker/flag SegWit" }

        val inputCount = ByteSerializer.readVarInt(buf)
        repeat(inputCount.toInt()) {
            buf.position(buf.position() + 32 + 4) // prevTxId + prevIndex
            val scriptSigLen = ByteSerializer.readVarInt(buf)
            buf.position(buf.position() + scriptSigLen.toInt() + 4) // scriptSig + sequence
        }

        val outputCount = ByteSerializer.readVarInt(buf)
        repeat(outputCount.toInt()) {
            buf.position(buf.position() + 8) // value
            val scriptLen = ByteSerializer.readVarInt(buf)
            buf.position(buf.position() + scriptLen.toInt())
        }

        // Witness do input 0 — stack com 1 item (assinatura Schnorr).
        val witnessItemCount = ByteSerializer.readVarInt(buf)
        require(witnessItemCount == 1L) { "esperava witness stack de 1 item (key-path), achei $witnessItemCount" }
        val sigLen = ByteSerializer.readVarInt(buf)
        val sig = ByteArray(sigLen.toInt())
        buf.get(sig)
        return sig
    }

    @Test
    fun `TxAssembler assina um UTXO Silent Payments com a chave certa, verificado por BIP340 independente`() {
        // Monta um output SP de verdade com o lado REMETENTE (já validado
        // contra os vetores oficiais em Bip352Test).
        val input1Priv = "eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1".hexToBytes()
        val input2Priv = "93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16".hexToBytes()
        val spendPriv  = "1d37787c2b7116ee983e9f9c13269df29091b391c04db94239e0d2bc2182c3bf".hexToBytes()
        val scanPriv   = "0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c".hexToBytes()
        val spendPub   = Secp256k1.publicKeyFromPrivate(spendPriv)
        val scanPub    = Secp256k1.publicKeyFromPrivate(scanPriv)

        val outpoints = listOf(
            Bip352.outpoint("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16".hexToBytes().reversedArray(), 0),
            Bip352.outpoint("a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d".hexToBytes().reversedArray(), 0)
        )
        val inputs = listOf(
            Bip352.SenderInput(input1Priv, isTaproot = false),
            Bip352.SenderInput(input2Priv, isTaproot = false)
        )
        val a = Bip352.sumSenderInputKeys(inputs)
        val senderSharedSecret = Bip352.senderSharedSecret(a, Bip352.smallestOutpoint(outpoints), scanPub)
        val pK = Bip352.outputPublicKey(spendPub, senderSharedSecret, 0)
        val outputXOnly = pK.copyOfRange(1, 33)
        val tweak = Bip352.outputTweak(senderSharedSecret, 0)

        // Lado DESTINATÁRIO: deriva o input pronto pra assinar a partir só
        // do tweak persistido (o que a wallet realmente guarda depois do
        // scan — ver SilentPaymentsConfirmer.ConfirmedUtxo).
        val fakeUtxo = RemoteUtxo(
            txid        = "11".repeat(32),
            vout        = 0,
            valueSats   = 100_000L,
            confirmed   = true,
            blockHeight = 900_000
        )
        val spendable = TxAssembler.deriveSilentPaymentSpendableInput(fakeUtxo, tweak, spendPriv)

        // O scriptPubKey derivado tem que bater com o output que o
        // remetente realmente criou — senão a wallet estaria gastando um
        // output DIFERENTE do que recebeu.
        assertEquals((byteArrayOf(0x51, 0x20) + outputXOnly).toHex(), spendable.scriptPubKey.toHex())
        assertTrue(spendable.skipTaprootTweak)

        val destScript = byteArrayOf(0x51, 0x20) + ByteArray(32) { 0x77 }
        val (rawTxBytes, _) = TxAssembler.signAndFinalize(
            spendable = listOf(spendable),
            outputs   = listOf(TxOut(90_000L, destScript)),
            spendType = SpendType.BIP86
        )

        val sig = extractTapKeySig(rawTxBytes)
        assertEquals(64, sig.size)

        // Recalcula o sighash de forma independente (mesma função de
        // produção, mas com a tx reconstruída aqui do zero — não reusa
        // nenhum estado interno do signAndFinalize).
        val unsignedTx = UnsignedTransaction(
            version  = 2,
            inputs   = listOf(TxIn(prevTxId = fakeUtxo.txid.hexToBytes().reversedArray(), prevIndex = 0, scriptSig = byteArrayOf(), sequence = 0xFFFFFFFFL)),
            outputs  = listOf(TxOut(90_000L, destScript)),
            lockTime = 0L
        )
        val sighash = TaprootSighashCalculator.calculate(
            tx         = unsignedTx,
            inputIndex = 0,
            utxos      = listOf(TxOut(100_000L, spendable.scriptPubKey))
        )

        assertTrue(
            "assinatura não verificou contra o output real via BIP340 independente — " +
                "provável double-tweak ou chave errada",
            verifySchnorrIndependently(outputXOnly, sighash, sig)
        )
    }
}
