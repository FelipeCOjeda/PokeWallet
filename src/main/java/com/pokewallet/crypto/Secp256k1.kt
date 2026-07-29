package com.pokewallet.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.ECPoint
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Security

/**
 * Secp256k1
 *
 * Suporte completo:
 *  - Public key (compressed)
 *  - ECDSA determinístico (BIP84)
 *  - LOW-S + DER (Bitcoin standard)
 *  - Schnorr real (BIP340 / Taproot)
 */
object Secp256k1 {

    // =================================================
    // Provider
    // =================================================

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // =================================================
    // Curva
    // =================================================

    private val CURVE = CustomNamedCurves.getByName("secp256k1")

    private val DOMAIN = ECDomainParameters(
        CURVE.curve,
        CURVE.g,
        CURVE.n,
        CURVE.h
    )

    private val N: BigInteger = CURVE.n
    private val HALF_N: BigInteger = N.shiftRight(1)

    // =================================================
    // Public keys
    // =================================================

    /**
     * Public key comprimida (33 bytes)
     */
    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32)
        val d = BigInteger(1, privateKey)
        val q: ECPoint = CURVE.g.multiply(d).normalize()
        return q.getEncoded(true)
    }

    /**
     * Public key x-only (32 bytes) — Taproot
     */
    fun xOnlyPublicKeyFromPrivate(privateKey: ByteArray): ByteArray =
        publicKeyFromPrivate(privateKey).copyOfRange(1, 33)

    // =================================================
    // ECDSA — BIP84
    // =================================================

    fun sign(
        privateKey: ByteArray,
        messageHash: ByteArray
    ): ByteArray {

        require(privateKey.size == 32)
        require(messageHash.size == 32)

        val d = BigInteger(1, privateKey)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, DOMAIN))

        val (r, sRaw) = signer.generateSignature(messageHash)
        val s = if (sRaw > HALF_N) N.subtract(sRaw) else sRaw

        return derEncode(r, s)
    }

    // =================================================
    // Schnorr — BIP340 (REAL)
    // =================================================

    fun signSchnorr(
        privateKey: ByteArray,
        messageHash: ByteArray
    ): ByteArray {

        require(privateKey.size == 32)
        require(messageHash.size == 32)

        // 1) chave privada inicial
        val d0 = BigInteger(1, privateKey)

        // 2) ajuste de paridade (even-Y)
        val pub0 = CURVE.g.multiply(d0).normalize()
        val d = if (pub0.yCoord.toBigInteger().testBit(0)) {
            N.subtract(d0)
        } else d0

        val pub = CURVE.g.multiply(d).normalize()
        val px = pub.xCoord.toBigInteger()

        // 3) nonce determinístico (BIP340)
        val k0 = taggedHash(
            "BIP0340/nonce",
            d.toBytes32() + px.toBytes32() + messageHash
        ).toBigInt().mod(N)

        require(k0 != BigInteger.ZERO)

        // 4) ajuste de paridade do nonce
        val r0 = CURVE.g.multiply(k0).normalize()
        val k = if (r0.yCoord.toBigInteger().testBit(0)) {
            N.subtract(k0)
        } else k0

        val r = CURVE.g.multiply(k).normalize()
        val rx = r.xCoord.toBigInteger()

        // 5) challenge
        val e = taggedHash(
            "BIP0340/challenge",
            rx.toBytes32() + px.toBytes32() + messageHash
        ).toBigInt().mod(N)

        // 6) assinatura final
        val s = k.add(e.multiply(d)).mod(N)

        return rx.toBytes32() + s.toBytes32()
    }

    // =================================================
    // Taproot key-path tweak (BIP341, sem script tree)
    // =================================================

    /**
     * Tweaka a chave privada pro key-path spend de um output Taproot
     * SEM script tree (só key-path — é o único modo que este app usa).
     *
     * Segue taproot_tweak_seckey do BIP341: ajusta a paridade de P=d*G,
     * soma tagged_hash("TapTweak", x(P)). A paridade FINAL de Q=d'*G é
     * resolvida dentro de signSchnorr() (que já faz esse ajuste sozinho),
     * então o valor retornado aqui pode ser usado diretamente como
     * privateKey de signSchnorr().
     */
    fun taprootTweakPrivateKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32)

        val d0 = BigInteger(1, privateKey)
        val p = CURVE.g.multiply(d0).normalize()
        val d = if (p.yCoord.toBigInteger().testBit(0)) N.subtract(d0) else d0

        val px = p.xCoord.toBigInteger().toBytes32()
        val t = taggedHash("TapTweak", px).toBigInt().mod(N)

        return d.add(t).mod(N).toBytes32()
    }

    /**
     * Deriva a output key Taproot (x-only, 32 bytes) a partir de uma
     * chave pública x-only interna — usado pra endereço watch-only
     * (a partir do xpub, sem chave privada). Mesmo tweak do BIP341,
     * aplicado sobre o ponto público (lift_x com Y par) em vez do escalar.
     */
    fun taprootOutputKeyFromInternalXOnly(xOnlyPubKey: ByteArray): ByteArray {
        require(xOnlyPubKey.size == 32)

        // lift_x: força Y par (prefixo 0x02) — mesma convenção do BIP340/341
        val p = CURVE.curve.decodePoint(byteArrayOf(0x02) + xOnlyPubKey).normalize()
        val t = taggedHash("TapTweak", xOnlyPubKey).toBigInt().mod(N)
        val q = p.add(CURVE.g.multiply(t)).normalize()

        return q.xCoord.toBigInteger().toBytes32()
    }

    // =================================================
    // Tagged Hash (BIP340)
    // =================================================

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray())
        return sha256(tagHash + tagHash + data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // =================================================
    // DER helpers (ECDSA)
    // =================================================

    private fun derEncode(r: BigInteger, s: BigInteger): ByteArray {
        val rEnc = encodeDerInt(r)
        val sEnc = encodeDerInt(s)

        val out = ByteArrayOutputStream()
        out.write(0x30)
        out.write(rEnc.size + sEnc.size)
        out.write(rEnc)
        out.write(sEnc)

        return out.toByteArray()
    }

    private fun encodeDerInt(v: BigInteger): ByteArray {
        // BigInteger.toByteArray() is already canonical two's complement:
        // it prepends 0x00 only when the MSB of the minimal representation is 1
        // (to indicate positive). Adding another 0x00 produces non-canonical DER.
        val b = v.toByteArray()
        return byteArrayOf(0x02, b.size.toByte()) + b
    }

    // =================================================
    // Utils
    // =================================================

    private fun ByteArray.toBigInt(): BigInteger =
        BigInteger(1, this)

    private fun BigInteger.toBytes32(): ByteArray {
        val b = this.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }
}

