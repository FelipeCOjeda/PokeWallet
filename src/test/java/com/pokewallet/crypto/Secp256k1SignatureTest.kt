package com.pokewallet.crypto

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Achado ALTO da auditoria (item 9): Secp256k1.sign()/signSchnorr() nunca
 * tinham teste cobrindo a ASSINATURA em si — os testes existentes
 * (SegwitSignerBip143Test, TaprootSighashCalculatorTest) só cobrem o
 * SIGHASH (a mensagem que entra na assinatura), não a assinatura em si.
 * Um bug na matemática (sinal errado, tag hash errada, k não
 * determinístico) passaria batido.
 *
 * Não existe um vetor "oficial" publicado pra bater byte-a-byte com o que
 * este app produz:
 *  - ECDSA usa nonce determinístico (RFC6979/HMacDSAK) — não há vetor
 *    público pra essa combinação exata de curva+hash+privkey usada aqui.
 *  - Secp256k1.signSchnorr() é uma variante do BIP340 DELIBERADAMENTE
 *    simplificada (ver SchnorrSigner.kt: nonce = tagged_hash(d||px||msg),
 *    SEM o passo aux_rand XOR do BIP340 padrão) — os vetores oficiais do
 *    BIP340 (bitcoin/bips test-vectors.csv) usam aux_rand e por isso NÃO
 *    batem com o nosso nonce, mesmo produzindo assinaturas igualmente
 *    válidas.
 *
 * Estratégia adotada (mais forte que decorar um vetor fixo — cobre várias
 * chaves/mensagens e detecta uma classe maior de bug):
 *  - conhecido/oficial: d=1 tem que gerar o PONTO GERADOR da curva —
 *    constante pública universal do secp256k1, não algo específico deste
 *    app (fonte de verdade independente de qualquer teste anterior).
 *  - ECDSA: verifica a assinatura por um caminho totalmente INDEPENDENTE
 *    do que a gerou (ECDSASigner.verifySignature, não .generateSignature)
 *    depois de decodificar "na unha" o DER que Secp256k1.sign() produziu.
 *  - Schnorr: reimplementa a verificação BIP340 do zero (do jeito que
 *    QUALQUER verificador Taproot real faz), sem chamar nenhuma função de
 *    Secp256k1.kt — não é "a função confirmando a si mesma".
 *  - As duas: determinismo (mesma entrada -> mesma saída sempre, RFC6979
 *    exige isso) e detecção de violação (1 bit alterado tem que invalidar).
 */
class Secp256k1SignatureTest {

    private val CURVE = CustomNamedCurves.getByName("secp256k1")
    private val DOMAIN = ECDomainParameters(CURVE.curve, CURVE.g, CURVE.n, CURVE.h)
    private val N = CURVE.n

    // Primo do corpo do secp256k1 (2^256 - 2^32 - 977) — constante pública
    // universal, usada aqui só pro Verify() do BIP340 (checagem r < p).
    private val FIELD_P = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun privateKey(n: Long): ByteArray = BigInteger.valueOf(n).toBytes32()

    private fun BigInteger.toBytes32(): ByteArray {
        val b = toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    // ── chave pública: constante universal conhecida ──

    @Test
    fun `d=1 produz o ponto gerador da curva - constante publica conhecida do secp256k1`() {
        val pub = Secp256k1.publicKeyFromPrivate(privateKey(1))
        assertEquals(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            pub.toHex()
        )
    }

    // ── ECDSA (BIP84) ──

    /** Decodifica o DER produzido por Secp256k1.sign() de volta pra (r, s) — sem reusar nenhum código de produção (Secp256k1.derEncode é privado, isto é uma leitura independente do mesmo formato). */
    private fun decodeDerRS(der: ByteArray): Pair<BigInteger, BigInteger> {
        require(der[0] == 0x30.toByte()) { "não começa com SEQUENCE (0x30)" }
        var i = 2 // pula 0x30 + byte de tamanho total
        require(der[i] == 0x02.toByte()) { "primeiro INTEGER (r) ausente" }
        val rLen = der[i + 1].toInt()
        val r = BigInteger(1, der.copyOfRange(i + 2, i + 2 + rLen))
        i += 2 + rLen
        require(der[i] == 0x02.toByte()) { "segundo INTEGER (s) ausente" }
        val sLen = der[i + 1].toInt()
        val s = BigInteger(1, der.copyOfRange(i + 2, i + 2 + sLen))
        return r to s
    }

    private fun verifyEcdsaIndependently(pubKeyCompressed: ByteArray, hash: ByteArray, der: ByteArray): Boolean {
        val (r, s) = decodeDerRS(der)
        val point = CURVE.curve.decodePoint(pubKeyCompressed)
        val verifier = ECDSASigner() // sem calculador de k — só usado pra verify, não assina aqui
        verifier.init(false, ECPublicKeyParameters(point, DOMAIN))
        return verifier.verifySignature(hash, r, s)
    }

    @Test
    fun `sign produz assinatura que verifica por um verificador ECDSA independente`() {
        val priv = privateKey(12345)
        val pub  = Secp256k1.publicKeyFromPrivate(priv)
        val hash = sha256("mensagem de teste".toByteArray())

        val sig = Secp256k1.sign(priv, hash)

        assertTrue(verifyEcdsaIndependently(pub, hash, sig))
    }

    @Test
    fun `sign e deterministico - mesma privkey e hash sempre produzem a mesma assinatura (RFC6979)`() {
        val priv = privateKey(999)
        val hash = sha256("outra mensagem".toByteArray())

        val sig1 = Secp256k1.sign(priv, hash)
        val sig2 = Secp256k1.sign(priv, hash)

        assertArrayEquals(sig1, sig2)
    }

    @Test
    fun `sign usa low-S (padrao Bitcoin) - s nunca maior que N div 2`() {
        val priv = privateKey(42)
        val hash = sha256("qualquer coisa".toByteArray())

        val (_, s) = decodeDerRS(Secp256k1.sign(priv, hash))

        assertTrue(s <= N.shiftRight(1))
    }

    @Test
    fun `assinatura ECDSA nao verifica se a mensagem for trocada depois de assinada`() {
        val priv = privateKey(7)
        val pub  = Secp256k1.publicKeyFromPrivate(priv)
        val hash = sha256("mensagem original".toByteArray())
        val hashAdulterado = sha256("mensagem adulterada".toByteArray())

        val sig = Secp256k1.sign(priv, hash)

        assertFalse(verifyEcdsaIndependently(pub, hashAdulterado, sig))
    }

    @Test
    fun `assinatura ECDSA nao verifica contra a chave publica errada`() {
        val priv1 = privateKey(11)
        val pub2  = Secp256k1.publicKeyFromPrivate(privateKey(22))
        val hash  = sha256("mensagem".toByteArray())

        val sig = Secp256k1.sign(priv1, hash)

        assertFalse(verifyEcdsaIndependently(pub2, hash, sig))
    }

    // ── Schnorr (BIP340, variante simplificada deste app — ver doc da classe) ──

    private fun taggedHashIndependent(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray())
        return sha256(tagHash + tagHash + data)
    }

    /** Reimplementação independente do Verify() do BIP340 (bitcoin/bips) —
     *  não chama NENHUMA função de Secp256k1.kt, só bouncycastle puro pra
     *  aritmética de curva, garantindo que é uma checagem de verdade e não
     *  "a função confirmando a si mesma". */
    private fun verifySchnorrIndependently(xOnlyPubKey: ByteArray, msg32: ByteArray, sig64: ByteArray): Boolean {
        val rBytes = sig64.copyOfRange(0, 32)
        val rBig = BigInteger(1, rBytes)
        val sBig = BigInteger(1, sig64.copyOfRange(32, 64))
        if (rBig >= FIELD_P || sBig >= N) return false

        val point = try {
            // lift_x do BIP340: força Y par via prefixo 0x02 (mesma convenção usada em produção, ex.: taprootOutputKeyFromInternalXOnly)
            CURVE.curve.decodePoint(byteArrayOf(0x02) + xOnlyPubKey).normalize()
        } catch (_: Exception) {
            return false
        }

        val e = BigInteger(1, taggedHashIndependent("BIP0340/challenge", rBytes + xOnlyPubKey + msg32)).mod(N)

        // R = s*G - e*P = s*G + (N-e)*P
        val sG = CURVE.g.multiply(sBig)
        val eP = point.multiply(N.subtract(e).mod(N))
        val r = sG.add(eP).normalize()

        if (r.isInfinity) return false
        if (r.yCoord.toBigInteger().testBit(0)) return false // R.y tem que ser par (BIP340)
        return r.xCoord.toBigInteger() == rBig
    }

    @Test
    fun `signSchnorr produz assinatura que verifica por um verificador BIP340 independente`() {
        val priv = privateKey(555)
        val pubXOnly = Secp256k1.xOnlyPublicKeyFromPrivate(priv)
        val msg = sha256("mensagem schnorr".toByteArray())

        val sig = Secp256k1.signSchnorr(priv, msg)

        assertTrue(verifySchnorrIndependently(pubXOnly, msg, sig))
    }

    @Test
    fun `signSchnorr e deterministico - mesma privkey e mensagem sempre produzem a mesma assinatura`() {
        val priv = privateKey(2024)
        val msg  = sha256("determinismo".toByteArray())

        val sig1 = Secp256k1.signSchnorr(priv, msg)
        val sig2 = Secp256k1.signSchnorr(priv, msg)

        assertArrayEquals(sig1, sig2)
    }

    @Test
    fun `assinatura Schnorr nao verifica se um bit da assinatura for alterado`() {
        val priv = privateKey(8)
        val pubXOnly = Secp256k1.xOnlyPublicKeyFromPrivate(priv)
        val msg = sha256("mais uma mensagem".toByteArray())

        val sig = Secp256k1.signSchnorr(priv, msg)
        val adulterada = sig.copyOf()
        adulterada[0] = (adulterada[0].toInt() xor 0x01).toByte()

        assertFalse(verifySchnorrIndependently(pubXOnly, msg, adulterada))
    }

    @Test
    fun `assinatura Schnorr nao verifica contra a chave publica errada`() {
        val priv1 = privateKey(9)
        val pub2XOnly = Secp256k1.xOnlyPublicKeyFromPrivate(privateKey(10))
        val msg = sha256("mensagem".toByteArray())

        val sig = Secp256k1.signSchnorr(priv1, msg)

        assertFalse(verifySchnorrIndependently(pub2XOnly, msg, sig))
    }

    @Test
    fun `signSchnorr funciona pra varias chaves e mensagens diferentes`() {
        for (k in listOf(1L, 2L, 100L, 999_999L)) {
            val priv = privateKey(k)
            val pubXOnly = Secp256k1.xOnlyPublicKeyFromPrivate(priv)
            val msg = sha256("mensagem $k".toByteArray())

            val sig = Secp256k1.signSchnorr(priv, msg)

            assertTrue("falhou pra privkey=$k", verifySchnorrIndependently(pubXOnly, msg, sig))
        }
    }
}
