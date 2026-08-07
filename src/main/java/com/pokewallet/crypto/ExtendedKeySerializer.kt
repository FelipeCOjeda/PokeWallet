package com.pokewallet.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

object ExtendedKeySerializer {

    // -------------------------
    // Version bytes
    // -------------------------

    private val XPUB = byteArrayOf(
        0x04.toByte(), 0x88.toByte(), 0xB2.toByte(), 0x1E.toByte()
    )

    private val ZPUB = byteArrayOf(
        0x04.toByte(), 0xB2.toByte(), 0x47.toByte(), 0x46.toByte()
    )

    // 0x04B2430C (valor anterior aqui) é na verdade "zprv" (chave PRIVADA
    // mainnet BIP84) — bug pré-existente, nunca usado de verdade em
    // produção antes de toDisplayPrefix() (achado pelo teste
    // ExtendedKeySerializerDisplayPrefixTest). Valor certo de "vpub"
    // (SLIP-132, BIP84 testnet PÚBLICA) é 0x045F1CF6.
    private val VPUB = byteArrayOf(
        0x04.toByte(), 0x5F.toByte(), 0x1C.toByte(), 0xF6.toByte()
    )

    private val TPUB = byteArrayOf(
        0x04.toByte(), 0x35.toByte(), 0x87.toByte(), 0xCF.toByte()
    )

    // -------------------------
    // Public API
    // -------------------------

    /**
     * Reescreve o prefixo (xpub/tpub → zpub/vpub) de uma extended public
     * key JÁ existente — só troca os 4 bytes de versão + recalcula o
     * checksum, NUNCA muda chain code/pubkey/depth/parent fingerprint (por
     * isso funciona em cima de qualquer xpub válido, não só os gerados por
     * este objeto). Usado SÓ pra EXIBIÇÃO (tela "Ver Chave Pública") — o
     * formato salvo em wallet.json continua "xpub" sempre, sem mudar
     * comportamento em nenhum outro lugar do app (XpubAddressDeriver.
     * decodeXpub() ignora os bytes de versão de qualquer forma, então o
     * prefixo é só cosmético). BIP86 (Taproot) não tem prefixo SLIP-132
     * padronizado — mantém xpub/tpub sem alteração.
     */
    fun toDisplayPrefix(xpub: String, spendType: SpendType, network: Network): String {
        if (spendType != SpendType.BIP84) return xpub
        val payload = com.pokewallet.Base58.decodeCheck(xpub)
        require(payload.size == 78) { "xpub inválido: payload ${payload.size} bytes (esperado 78)" }
        val version = if (network == Network.MAINNET) ZPUB else VPUB
        val newPayload = version + payload.copyOfRange(4, payload.size)
        return com.pokewallet.Base58.encode(newPayload + checksum(newPayload))
    }

    fun serializeXpub(key: HDKey): String =
        serialize(key, XPUB)

    fun serializeZpub(key: HDKey): String =
        serialize(key, ZPUB)

    fun serializeVpub(key: HDKey): String =
        serialize(key, VPUB)

    fun serializeTpub(key: HDKey): String =
        serialize(key, TPUB)

    // -------------------------
    // Core serializer (BIP32)
    // -------------------------

    private fun serialize(
        key: HDKey,
        version: ByteArray
    ): String {

        val pubKey = Secp256k1.publicKeyFromPrivate(key.privateKey)

        val out = ByteArrayOutputStream()

        out.write(version)                              // 4
        out.write(byteArrayOf(key.depth.toByte()))      // 1
        out.write(intToBytes(key.parentFingerprint))    // 4
        out.write(intToBytes(key.index))                // 4
        out.write(key.chainCode)                        // 32
        out.write(pubKey)                               // 33

        val payload = out.toByteArray()
        val checksum = checksum(payload)

        // ✅ retorno correto + variável correta
        return com.pokewallet.Base58.encode(payload + checksum)
    }

    // -------------------------
    // Utils
    // -------------------------

    private fun intToBytes(i: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .putInt(i)
            .array()

    private fun checksum(data: ByteArray): ByteArray {
        val hash = sha256(sha256(data))
        return hash.copyOfRange(0, 4)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
