package com.pokewallet.crypto

data class HDKey(
    val privateKey: ByteArray,
    val chainCode: ByteArray,
    val depth: Int,
    val index: Int,
    val parentFingerprint: Int
) {
    // data class gera equals()/hashCode() referenciais pra ByteArray (compara
    // identidade do objeto, não conteúdo) — duas chaves com os mesmos bytes
    // seriam tratadas como diferentes. Override manual com contentEquals/
    // contentHashCode pra comparação estrutural correta.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HDKey) return false
        return privateKey.contentEquals(other.privateKey) &&
            chainCode.contentEquals(other.chainCode) &&
            depth == other.depth &&
            index == other.index &&
            parentFingerprint == other.parentFingerprint
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + chainCode.contentHashCode()
        result = 31 * result + depth
        result = 31 * result + index
        result = 31 * result + parentFingerprint
        return result
    }
}
