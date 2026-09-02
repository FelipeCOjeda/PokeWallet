package com.pokewallet.crypto

/**
 * Endereço Silent Payments (BIP-352) — bech32m com payload plano de 66
 * bytes (scan pubkey || spend pubkey, ambas comprimidas 33 bytes) e um
 * nibble de versão (v0 = "q", igual o resto do payload é tratado como
 * "dado" bech32m — não tem semântica de witness-version como os
 * endereços SegWit/Taproot).
 *
 * HRP confirmado direto do texto do BIP-352: "sp" mainnet, "tsp" pra
 * testnet/signet. A BIP não formaliza um HRP pra regtest — "sprt" segue a
 * convenção comum de outras implementações (ex: análogo a "bcrt" pros
 * endereços normais desta wallet), isolado só nesta função pra ser fácil
 * de ajustar se divergir de alguma referência específica.
 */
object SilentPaymentAddress {

    private const val VERSION = 0

    data class Decoded(val scanPubKey: ByteArray, val spendPubKey: ByteArray)

    fun hrpFor(network: Network): String = when (network) {
        Network.MAINNET -> "sp"
        Network.TESTNET -> "tsp"
        Network.REGTEST -> "sprt"
    }

    fun encode(scanPubKey: ByteArray, spendPubKey: ByteArray, network: Network): String {
        require(scanPubKey.size == 33) { "scan pubkey precisa ter 33 bytes (comprimida)" }
        require(spendPubKey.size == 33) { "spend pubkey precisa ter 33 bytes (comprimida)" }
        val payload = scanPubKey + spendPubKey
        val payload5 = Bech32.convertBits(payload, 8, 5, true)
        val data = IntArray(payload5.size + 1)
        data[0] = VERSION
        payload5.copyInto(data, 1)
        return Bech32.encodeBech32m(hrpFor(network), data)
    }

    fun decode(address: String, network: Network): Decoded {
        val (hrp, data) = Bech32.decode(address) ?: error("Endereço Silent Payments inválido: $address")
        require(hrp == hrpFor(network)) {
            "Endereço Silent Payments é de outra rede (prefixo \"$hrp\", esperado \"${hrpFor(network)}\") — confira se não colou um endereço testnet numa wallet mainnet (ou vice-versa)."
        }
        require(data.isNotEmpty())
        val version = data[0]
        require(version == VERSION) { "Versão de endereço Silent Payments não suportada: $version" }
        val payload5 = data.copyOfRange(1, data.size)
        val payload5Bytes = ByteArray(payload5.size) { payload5[it].toByte() }
        val payloadInts = Bech32.convertBits(payload5Bytes, 5, 8, false)
        val payload = ByteArray(payloadInts.size) { payloadInts[it].toByte() }
        require(payload.size == 66) { "Payload de endereço Silent Payments com tamanho inválido: ${payload.size} bytes (esperado 66)" }
        return Decoded(
            scanPubKey = payload.copyOfRange(0, 33),
            spendPubKey = payload.copyOfRange(33, 66)
        )
    }

    /**
     * Detecção barata de "isso parece um endereço Silent Payments" pro
     * despacho de destino no fluxo de envio — não valida checksum/rede,
     * só o prefixo, pra decidir qual caminho seguir antes de decodificar
     * de verdade (a decodificação real já valida tudo e dá erro claro).
     */
    fun looksLikeSilentPaymentAddress(address: String): Boolean {
        val lower = address.trim().lowercase()
        return lower.startsWith("sp1") || lower.startsWith("tsp1") || lower.startsWith("sprt1")
    }
}
