package com.pokewallet.network

import com.pokewallet.Base58
import com.pokewallet.crypto.AddressBuilder
import com.pokewallet.crypto.Hashes
import com.pokewallet.crypto.Network
import com.pokewallet.crypto.Secp256k1
import org.bouncycastle.crypto.ec.CustomNamedCurves
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Deriva endereços Bitcoin a partir de um xpub — sem seed, sem chave privada.
 *
 * Implementa BIP32 public child key derivation (non-hardened apenas),
 * necessária para gerar endereços a partir da account key pública.
 *
 * Suporta: BIP84 (P2WPKH / bc1q) via chain = 0 (externo) ou 1 (interno).
 */
object XpubAddressDeriver {

    private val CURVE = CustomNamedCurves.getByName("secp256k1")

    // ── xpub decode ───────────────────────────────────────

    data class XpubKey(
        val pubKey: ByteArray,    // 33 bytes, comprimida
        val chainCode: ByteArray  // 32 bytes
    )

    /**
     * Decodifica xpub Base58Check → chave pública + chain code.
     *
     * Formato xpub (78 bytes de payload):
     *   4  version
     *   1  depth
     *   4  parent fingerprint
     *   4  child index
     *  32  chain code
     *  33  compressed public key
     */
    fun decodeXpub(xpub: String): XpubKey {
        val payload = Base58.decodeCheck(xpub)
        require(payload.size == 78) {
            "xpub inválido: payload ${payload.size} bytes (esperado 78)"
        }
        return XpubKey(
            chainCode = payload.copyOfRange(13, 45),
            pubKey    = payload.copyOfRange(45, 78)
        )
    }

    // ── BIP32 public child derivation ─────────────────────

    /**
     * Deriva uma chave pública filha não-hardened.
     *
     * BIP32 §Child key derivation (public):
     *   I  = HMAC-SHA512(key=chainCode, data=pubKey || ser32(index))
     *   IL = I[0:32]
     *   IR = I[32:64]
     *   childKey = point(IL) + parentKey  (EC point addition)
     *   childChainCode = IR
     */
    fun derivePublicChild(parent: XpubKey, index: Int): XpubKey {
        require(index >= 0) {
            "Derivação hardened impossível a partir de xpub (index=$index)"
        }

        val data = parent.pubKey + ser32(index)
        val I  = hmacSha512(parent.chainCode, data)
        val IL = I.copyOfRange(0, 32)
        val IR = I.copyOfRange(32, 64)

        val ilInt = BigInteger(1, IL)
        require(ilInt < CURVE.n) { "IL >= CURVE_N: chave inválida no índice $index" }

        val pointIL     = CURVE.g.multiply(ilInt).normalize()
        val parentPoint = CURVE.curve.decodePoint(parent.pubKey)
        val childPoint  = pointIL.add(parentPoint).normalize()

        require(!childPoint.isInfinity) { "Ponto filho é infinito no índice $index" }

        return XpubKey(
            pubKey    = childPoint.getEncoded(true),
            chainCode = IR
        )
    }

    // ── Endereços de alto nível ───────────────────────────

    /**
     * Deriva endereço P2WPKH (BIP84 / bc1q) a partir do xpub da conta.
     *
     * @param xpub      account-level xpub (m/84'/coin'/0')
     * @param chain     0 = externo (recebimento), 1 = interno (troco)
     * @param index     índice do endereço (0, 1, 2, ...)
     * @param network   MAINNET, TESTNET ou REGTEST
     */
    fun p2wpkhAddress(
        xpub: String,
        chain: Int,
        index: Int,
        network: Network
    ): String {
        val accountKey = decodeXpub(xpub)
        val chainKey   = derivePublicChild(accountKey, chain)
        val indexKey   = derivePublicChild(chainKey, index)
        val pubKeyHash = Hashes.hash160(indexKey.pubKey)
        return AddressBuilder.p2wpkh(pubKeyHash, network)
    }

    /**
     * Deriva endereço P2TR (BIP86 / bc1p) a partir do xpub da conta —
     * watch-only, sem chave privada. Aplica o tweak Taproot (BIP341)
     * key-path direto sobre o ponto público.
     *
     * @param xpub      account-level xpub (m/86'/coin'/0')
     * @param chain     0 = externo (recebimento), 1 = interno (troco)
     * @param index     índice do endereço (0, 1, 2, ...)
     * @param network   MAINNET, TESTNET ou REGTEST
     */
    fun p2trAddress(
        xpub: String,
        chain: Int,
        index: Int,
        network: Network
    ): String {
        val accountKey = decodeXpub(xpub)
        val chainKey   = derivePublicChild(accountKey, chain)
        val indexKey   = derivePublicChild(chainKey, index)
        val xOnly      = indexKey.pubKey.copyOfRange(1, 33)
        val outputKey  = Secp256k1.taprootOutputKeyFromInternalXOnly(xOnly)
        return AddressBuilder.p2tr(outputKey, network)
    }

    // ── Utilitários ───────────────────────────────────────

    private fun ser32(i: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(i).array()

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }
}
