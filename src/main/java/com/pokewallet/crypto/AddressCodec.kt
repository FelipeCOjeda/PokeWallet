package com.pokewallet.crypto

/** Direção inversa de [AddressBuilder]: endereço bech32/bech32m → scriptPubKey. */
object AddressCodec {

    fun addressToScriptPubKey(address: String, network: Network): ByteArray {
        val (hrp, data) = Bech32.decode(address) ?: error("Endereço inválido: $address")
        require(hrp == network.hrp) {
            "Endereço de destino é de outra rede (prefixo \"$hrp\", esperado \"${network.hrp}\") — confira se não colou um endereço testnet numa wallet mainnet (ou vice-versa)."
        }
        require(data.isNotEmpty())
        val witnessVersion = data[0].toInt()
        require(witnessVersion in 0..16) { "Versão de witness inválida no endereço: $witnessVersion" }
        val program5bit    = data.copyOfRange(1, data.size)
        val prog5Bytes     = ByteArray(program5bit.size) { program5bit[it].toByte() }
        val progInts       = Bech32.convertBits(prog5Bytes, 5, 8, false)
        val programBytes   = ByteArray(progInts.size) { progInts[it].toByte() }
        require(
            if (witnessVersion == 0) programBytes.size == 20 || programBytes.size == 32
            else programBytes.size in 2..40
        ) { "Tamanho de programa inválido pra witness v$witnessVersion no endereço: ${programBytes.size} bytes" }
        val versionOpcode  = if (witnessVersion == 0) 0x00.toByte() else (0x50 + witnessVersion).toByte()
        return byteArrayOf(versionOpcode, programBytes.size.toByte()) + programBytes
    }
}
