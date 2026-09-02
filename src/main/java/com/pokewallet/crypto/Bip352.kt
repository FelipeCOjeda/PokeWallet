package com.pokewallet.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Núcleo criptográfico do BIP-352 (Silent Payments) — fórmulas verificadas
 * direto do texto oficial (`bitcoin/bips`, `bip-0352.mediawiki`), não de
 * memória. Sem suporte a labels nesta v1: esta wallet já tem troco via
 * endereço BIP84/86 normal, então não precisa de auto-pagamento SP pro
 * troco (único cenário que exigiria o label ''m = 0'' do BIP).
 *
 * Convenções de byte usadas aqui (idênticas ao BIP):
 *  - outpoint (36 bytes) = txid interno little-endian (32B) || vout LE (4B)
 *  - ser_P(ponto) = pubkey comprimida SEC1 (33 bytes)
 *  - ser_32(i) = inteiro de 32 bits big-endian (4 bytes)
 */
object Bip352 {

    /**
     * Um input elegível do lado REMETENTE — já temos a chave privada, não
     * precisamos parsear scriptSig/witness (isso só é necessário do lado
     * ESCANEADOR, que não existe ainda nesta fase).
     *
     * ATENÇÃO — armadilha de fundo perdido: [privateKey] tem que ser a
     * chave privada que efetivamente controla a pubkey ON-CHAIN daquele
     * input, não a chave HD crua. Pra um input Taproot (BIP86) isso é a
     * chave JÁ TWEAKED (`Secp256k1.taprootTweakPrivateKey(rawKey)`), porque
     * a pubkey publicada na scriptPubKey é a output key tweaked, não a
     * internal key crua — [sumSenderInputKeys] só aplica a correção FINAL
     * de paridade par (a normalização que o próprio BIP-352 exige em cima
     * disso), não o tweak de Taproot em si. Pra um input SegWit v0
     * (BIP84) é a chave HD crua mesmo, sem tweak nenhum.
     */
    data class SenderInput(val privateKey: ByteArray, val isTaproot: Boolean)

    // =================================================
    // Tagged hash (BIP340, mesma fórmula do Secp256k1 privado —
    // duplicado aqui de propósito pra não mexer na visibilidade de um
    // arquivo de cripto já testado/auditado por só 3 linhas).
    // =================================================

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = Hashes.sha256(tag.toByteArray())
        return Hashes.sha256(tagHash + tagHash + data)
    }

    // =================================================
    // outpoint / ser_32
    // =================================================

    /** outpoint (36 bytes) = txid interno (LE, 32B) || vout (LE, 4B). */
    fun outpoint(internalTxidLE: ByteArray, vout: Int): ByteArray {
        require(internalTxidLE.size == 32) { "txid interno precisa ter 32 bytes" }
        val voutLE = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vout).array()
        return internalTxidLE + voutLE
    }

    /** outpoint_L — o menor outpoint lexicograficamente entre os inputs da tx. */
    fun smallestOutpoint(outpoints: List<ByteArray>): ByteArray {
        require(outpoints.isNotEmpty())
        outpoints.forEach { require(it.size == 36) { "outpoint precisa ter 36 bytes" } }
        return outpoints.reduce { a, b -> if (compareUnsigned(a, b) <= 0) a else b }
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return ai - bi
        }
        return 0
    }

    private fun ser32(i: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(i).array()

    // =================================================
    // input_hash = hash_BIP0352/Inputs(outpoint_L || A)
    // =================================================

    fun inputHash(smallestOutpoint: ByteArray, sumOfPublicKeys: ByteArray): ByteArray {
        require(smallestOutpoint.size == 36) { "outpoint_L precisa ter 36 bytes" }
        require(sumOfPublicKeys.size == 33) { "A precisa ser uma pubkey comprimida (33 bytes)" }
        val h = taggedHash("BIP0352/Inputs", smallestOutpoint + sumOfPublicKeys)
        return Secp256k1.hashToScalar(h)
    }

    // =================================================
    // t_k = hash_BIP0352/SharedSecret(ser_P(ecdh_shared_secret) || ser_32(k))
    // =================================================

    fun outputTweak(ecdhSharedSecret: ByteArray, k: Int): ByteArray {
        require(ecdhSharedSecret.size == 33) { "shared secret precisa ser um ponto comprimido (33 bytes)" }
        val h = taggedHash("BIP0352/SharedSecret", ecdhSharedSecret + ser32(k))
        return Secp256k1.hashToScalar(h)
    }

    // =================================================
    // Lado REMETENTE
    // =================================================

    /** Soma as chaves privadas dos inputs elegíveis, negando as taproot
     *  quando necessário (Y ímpar) — ''a = Σ aᵢ'' já normalizado. */
    fun sumSenderInputKeys(inputs: List<SenderInput>): ByteArray {
        require(inputs.isNotEmpty()) { "precisa de pelo menos um input elegível" }
        var sum: ByteArray? = null
        for (input in inputs) {
            val ai = if (input.isTaproot) Secp256k1.evenYPrivateKey(input.privateKey) else input.privateKey
            sum = if (sum == null) ai else Secp256k1.addScalars(sum, ai)
        }
        val a = sum!!
        require(a.any { it != 0.toByte() }) { "soma das chaves privadas dos inputs deu zero (probabilidade ~0)" }
        return a
    }

    /** ecdh_shared_secret do lado remetente = input_hash · a · B_scan. */
    fun senderSharedSecret(sumOfInputPrivateKeys: ByteArray, smallestOutpoint: ByteArray, scanPubKey: ByteArray): ByteArray {
        val sumPubKey = Secp256k1.multiplyGenerator(sumOfInputPrivateKeys) // A = a·G
        val h = inputHash(smallestOutpoint, sumPubKey)
        val combinedScalar = Secp256k1.multiplyScalars(sumOfInputPrivateKeys, h)
        return Secp256k1.pointMultiply(scanPubKey, combinedScalar)
    }

    /** P_k = B_spend + t_k·G — pubkey do output (33 bytes comprimida; o
     *  chamador extrai os 32 bytes x-only pro scriptPubKey P2TR). */
    fun outputPublicKey(spendPubKey: ByteArray, sharedSecret: ByteArray, k: Int): ByteArray {
        val tweak = outputTweak(sharedSecret, k)
        return Secp256k1.pointAdd(spendPubKey, Secp256k1.multiplyGenerator(tweak))
    }

    /**
     * Monta o scriptPubKey P2TR (0x51 0x20 <32B x-only>) do output pra um
     * endereço Silent Payments, dado o conjunto de inputs sendo gastos
     * (com suas chaves privadas — só o remetente consegue calcular isso,
     * nunca um dispositivo watch-only) e o índice ''k'' (0 pro caso comum
     * de um único output pro mesmo destinatário nesta tx).
     */
    fun deriveSenderOutputScript(
        inputs: List<SenderInput>,
        outpoints: List<ByteArray>,
        scanPubKey: ByteArray,
        spendPubKey: ByteArray,
        k: Int = 0
    ): ByteArray {
        val a = sumSenderInputKeys(inputs)
        val outpointL = smallestOutpoint(outpoints)
        val sharedSecret = senderSharedSecret(a, outpointL, scanPubKey)
        val outputKey = outputPublicKey(spendPubKey, sharedSecret, k)
        val xOnly = outputKey.copyOfRange(1, 33)
        return byteArrayOf(0x51, 0x20) + xOnly
    }

    // =================================================
    // Lado DESTINATÁRIO (usado pelo scanner, fase futura — implementado
    // já agora porque os vetores de teste oficiais cobrem os dois lados,
    // e a simetria das fórmulas é a melhor validação de que o lado
    // remetente está certo).
    // =================================================

    /** ecdh_shared_secret do lado destinatário = input_hash · b_scan · A,
     *  onde A é a soma das pubkeys PÚBLICAS dos inputs (extraídas da tx
     *  pelo scanner — não implementado nesta fase). */
    fun receiverSharedSecret(scanPrivateKey: ByteArray, smallestOutpoint: ByteArray, sumOfInputPublicKeys: ByteArray): ByteArray {
        val h = inputHash(smallestOutpoint, sumOfInputPublicKeys)
        val combinedScalar = Secp256k1.multiplyScalars(scanPrivateKey, h)
        return Secp256k1.pointMultiply(sumOfInputPublicKeys, combinedScalar)
    }

    /** d = (b_spend + t_k) mod n, ajustado pra Y par — chave privada real
     *  que gasta o output P_k. */
    fun spendingPrivateKey(spendPrivateKey: ByteArray, sharedSecret: ByteArray, k: Int): ByteArray {
        val tweak = outputTweak(sharedSecret, k)
        val d0 = Secp256k1.addScalars(spendPrivateKey, tweak)
        return Secp256k1.evenYPrivateKey(d0)
    }
}
