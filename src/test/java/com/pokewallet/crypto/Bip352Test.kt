package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Valida o núcleo do BIP-352 (Silent Payments) contra um subconjunto dos
 * vetores de teste OFICIAIS (bitcoin/bips,
 * bip-0352/send_and_receive_test_vectors.json — valores extraídos
 * programaticamente do JSON oficial (nunca digitados à mão) e conferidos
 * independentemente em Python com `coincurve` antes de portar pra cá,
 * exatamente pelo motivo que este arquivo existe: bug aqui é fundo
 * perdido, não "parece razoável".
 *
 * Casos escolhidos cobrem os pontos que mais fácil dão errado:
 *  - soma simples de chaves não-taproot (vetor "Simple send: two inputs")
 *  - negociação de paridade Y em inputs taproot mistos (vetor "taproot
 *    only with mixed even/odd y-values")
 *  - múltiplos outputs pro mesmo destinatário, ou seja, o loop de k
 *    (vetor "Multiple outputs: multiple outputs, same recipient")
 *  - soma de chaves dando zero → deve falhar (vetor "Input keys sum up to
 *    zero / point at infinity")
 *  - simetria remetente/destinatário (o "priv_key_tweak" do lado
 *    destinatário é exatamente t_k, não d = b_spend + t_k — conferido em
 *    Python antes de escrever esta asserção)
 */
class Bip352Test {

    private fun outpointOf(txidDisplayHex: String, vout: Int): ByteArray =
        Bip352.outpoint(txidDisplayHex.hexToBytes().reversedArray(), vout)

    // -------------------------------------------------------------
    // Vetor "Simple send: two inputs" — dois inputs P2PKH (não-taproot)
    // -------------------------------------------------------------

    @Test
    fun `simple send with two non-taproot inputs matches official vector`() {
        val priv1 = "eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1".hexToBytes()
        val priv2 = "93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16".hexToBytes()
        val outpoints = listOf(
            outpointOf("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16", 0),
            outpointOf("a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d", 0)
        )
        val scanPubKey = "0220bcfac5b99e04ad1a06ddfb016ee13582609d60b6291e98d01a9bc9a16c96d4".hexToBytes()
        val spendPubKey = "025cc9856d6f8375350e123978daac200c260cb5b5ae83106cab90484dcd8fcf36".hexToBytes()
        val address = "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"

        val inputs = listOf(
            Bip352.SenderInput(priv1, isTaproot = false),
            Bip352.SenderInput(priv2, isTaproot = false)
        )

        val a = Bip352.sumSenderInputKeys(inputs)
        assertEquals("7ed265a6dac7aba8508a32d6d6b84c7f1dbd0a0941dd01088d69e8d556345f86", a.toHex())

        val sharedSecret = Bip352.senderSharedSecret(a, Bip352.smallestOutpoint(outpoints), scanPubKey)
        assertEquals("028158aff7d61ea66b2fa7f555bc3c5937d1debbde16423d630f9aa7943e14d80d", sharedSecret.toHex())

        val script = Bip352.deriveSenderOutputScript(inputs, outpoints, scanPubKey, spendPubKey)
        assertEquals("51203e9fce73d4e77a4809908e3c3a2e54ee147b9312dc5044a193d1fc85de46e3c1", script.toHex())

        val decoded = SilentPaymentAddress.decode(address, Network.MAINNET)
        assertEquals(scanPubKey.toHex(), decoded.scanPubKey.toHex())
        assertEquals(spendPubKey.toHex(), decoded.spendPubKey.toHex())
        assertEquals(address, SilentPaymentAddress.encode(scanPubKey, spendPubKey, Network.MAINNET))
    }

    // -------------------------------------------------------------
    // Vetor "Single recipient: taproot only with mixed even/odd y-values"
    // -------------------------------------------------------------

    @Test
    fun `taproot inputs with mixed even-odd y-values negate correctly`() {
        val priv1 = "eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1".hexToBytes()
        val priv2 = "1d37787c2b7116ee983e9f9c13269df29091b391c04db94239e0d2bc2182c3bf".hexToBytes()
        val outpoints = listOf(
            outpointOf("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16", 0),
            outpointOf("a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d", 0)
        )
        val scanPubKey = "0220bcfac5b99e04ad1a06ddfb016ee13582609d60b6291e98d01a9bc9a16c96d4".hexToBytes()
        val spendPubKey = "025cc9856d6f8375350e123978daac200c260cb5b5ae83106cab90484dcd8fcf36".hexToBytes()

        val inputs = listOf(
            Bip352.SenderInput(priv1, isTaproot = true),
            Bip352.SenderInput(priv2, isTaproot = true)
        )

        val a = Bip352.sumSenderInputKeys(inputs)
        assertEquals("cda4ff9a3480e1fbfc6edd61b222f280f9baa0652002c1ffdb612efcc45d2ff2", a.toHex())

        val sharedSecret = Bip352.senderSharedSecret(a, Bip352.smallestOutpoint(outpoints), scanPubKey)
        assertEquals("030e7f5ca4bf109fc35c8c2d878f756c891ac04c456cc5f0b05fcec4d3b2b1beb2", sharedSecret.toHex())

        val script = Bip352.deriveSenderOutputScript(inputs, outpoints, scanPubKey, spendPubKey)
        assertEquals("512077cab7dd12b10259ee82c6ea4b509774e33e7078e7138f568092241bf26b99f1", script.toHex())
    }

    // -------------------------------------------------------------
    // Vetor "Multiple outputs: multiple outputs, same recipient" — testa
    // o loop de k (dois outputs pro mesmo destinatário na mesma tx).
    // A ordem dos dois outputs esperados no vetor oficial NÃO segue a
    // ordem de k (é ordenada de outro jeito) — por isso a comparação
    // aqui é por CONJUNTO (sorted), não por índice posicional.
    // -------------------------------------------------------------

    @Test
    fun `multiple outputs to same recipient loop k correctly`() {
        val priv1 = "eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1".hexToBytes()
        val priv2 = "0378e95685b74565fa56751b84a32dfd18545d10d691641b8372e32164fad66a".hexToBytes()
        val outpoints = listOf(
            outpointOf("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16", 0),
            outpointOf("a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d", 0)
        )
        val scanPubKey = "0220bcfac5b99e04ad1a06ddfb016ee13582609d60b6291e98d01a9bc9a16c96d4".hexToBytes()
        val spendPubKey = "025cc9856d6f8375350e123978daac200c260cb5b5ae83106cab90484dcd8fcf36".hexToBytes()

        val inputs = listOf(
            Bip352.SenderInput(priv1, isTaproot = false),
            Bip352.SenderInput(priv2, isTaproot = false)
        )

        val a = Bip352.sumSenderInputKeys(inputs)
        assertEquals("ee55616ce5a93e508f03f21949ecbe70a2a0b107b6e1df5d98b4e4da4adaca1b", a.toHex())

        val sharedSecret = Bip352.senderSharedSecret(a, Bip352.smallestOutpoint(outpoints), scanPubKey)
        // mesmo shared secret reutilizado pros dois outputs — só o k (e
        // portanto o tweak) muda entre eles.
        assertEquals("038efbcbc1b0938fba3bf59fea1219a3c54b6d6f9107560da05001407adc13f413", sharedSecret.toHex())

        val outputK0 = Bip352.outputPublicKey(spendPubKey, sharedSecret, 0).copyOfRange(1, 33).toHex()
        val outputK1 = Bip352.outputPublicKey(spendPubKey, sharedSecret, 1).copyOfRange(1, 33).toHex()

        val expected = listOf(
            "e976a58fbd38aeb4e6093d4df02e9c1de0c4513ae0c588cef68cda5b2f8834ca",
            "f207162b1a7abc51c42017bef055e9ec1efc3d3567cb720357e2b84325db33ac"
        ).sorted()
        assertEquals(expected, listOf(outputK0, outputK1).sorted())
    }

    // -------------------------------------------------------------
    // Vetor "Input keys sum up to zero / point at infinity" — dois
    // inputs P2WPKH com chaves privadas d e (n-d): a soma dá zero e o
    // envio TEM que falhar (não silenciosamente aceitar).
    // -------------------------------------------------------------

    @Test
    fun `input private keys summing to zero fails closed`() {
        val priv1 = "a6df6a0bb448992a301df4258e06a89fe7cf7146f59ac3bd5ff26083acb22ceb".hexToBytes()
        val priv2 = "592095f44bb766d5cfe20bda71f9575ed2df6b9fb9addc7e5fdffe0923841456".hexToBytes()

        val inputs = listOf(
            Bip352.SenderInput(priv1, isTaproot = false),
            Bip352.SenderInput(priv2, isTaproot = false)
        )

        assertThrows(IllegalArgumentException::class.java) {
            Bip352.sumSenderInputKeys(inputs)
        }
    }

    // -------------------------------------------------------------
    // Simetria remetente/destinatário: o "priv_key_tweak" do lado
    // destinatário no vetor oficial é exatamente t_k (não b_spend + t_k)
    // — conferido em Python (coincurve) antes de escrever esta asserção,
    // pra não travestir um erro de entendimento como "vetor bateu".
    // -------------------------------------------------------------

    @Test
    fun `receiver shared secret and tweak match sender side`() {
        val scanPrivKey = "0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c".hexToBytes()
        val sumOfInputPubKeys = "032562c1ab2d6bd45d7ca4d78f569999e5333dffd3ac5263924fd00d00dedc4bee".hexToBytes()
        val outpoints = listOf(
            outpointOf("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16", 0),
            outpointOf("a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d", 0)
        )

        val sharedSecret = Bip352.receiverSharedSecret(scanPrivKey, Bip352.smallestOutpoint(outpoints), sumOfInputPubKeys)
        assertEquals("028158aff7d61ea66b2fa7f555bc3c5937d1debbde16423d630f9aa7943e14d80d", sharedSecret.toHex())

        val tweak = Bip352.outputTweak(sharedSecret, 0)
        assertEquals("f438b40179a3c4262de12986c0e6cce0634007cdc79c1dcd3e20b9ebc2e7eef6", tweak.toHex())
    }

    // -------------------------------------------------------------
    // receiverSharedSecretFromPrecomputedTweak (Fase 3, blindbit-oracle):
    // quando o servidor já manda input_hash·A pré-computado, multiplicar
    // isso pela scan private key tem que dar EXATAMENTE o mesmo shared
    // secret que calcular do zero com receiverSharedSecret — mesmo vetor
    // oficial do teste acima, só trocando o caminho de cálculo.
    // -------------------------------------------------------------

    @Test
    fun `receiverSharedSecretFromPrecomputedTweak bate com receiverSharedSecret calculado do zero`() {
        val scanPrivKey = "0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c".hexToBytes()
        val sumOfInputPubKeys = "032562c1ab2d6bd45d7ca4d78f569999e5333dffd3ac5263924fd00d00dedc4bee".hexToBytes()
        val outpoints = listOf(
            outpointOf("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16", 0),
            outpointOf("a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d", 0)
        )
        val outpointL = Bip352.smallestOutpoint(outpoints)

        val expected = Bip352.receiverSharedSecret(scanPrivKey, outpointL, sumOfInputPubKeys)

        // "tweak" que um indexador tipo blindbit-oracle mandaria: input_hash·A
        // já pré-multiplicado, sem o cliente precisar saber outpoint_L/A.
        val h = Bip352.inputHash(outpointL, sumOfInputPubKeys)
        val precomputedTweak = Secp256k1.pointMultiply(sumOfInputPubKeys, h)

        val actual = Bip352.receiverSharedSecretFromPrecomputedTweak(scanPrivKey, precomputedTweak)
        assertEquals(expected.toHex(), actual.toHex())
    }

    // -------------------------------------------------------------
    // spendingPrivateKeyFromTweak (Fase 3, lado de gasto): a wallet só
    // persiste t_k (não o shared secret inteiro, ver
    // SilentPaymentsConfirmer.ConfirmedUtxo), então gastar depois do scan
    // sempre passa por aqui — tem que dar EXATAMENTE a mesma chave que
    // spendingPrivateKey(spendPriv, sharedSecret, k) calculada do zero.
    // -------------------------------------------------------------

    @Test
    fun `spendingPrivateKeyFromTweak bate com spendingPrivateKey calculado do shared secret`() {
        val spendPrivKey = "1d37787c2b7116ee983e9f9c13269df29091b391c04db94239e0d2bc2182c3bf".hexToBytes()
        val sharedSecret = "028158aff7d61ea66b2fa7f555bc3c5937d1debbde16423d630f9aa7943e14d80d".hexToBytes()

        val expected = Bip352.spendingPrivateKey(spendPrivKey, sharedSecret, 0)

        val tweak = Bip352.outputTweak(sharedSecret, 0)
        val actual = Bip352.spendingPrivateKeyFromTweak(spendPrivKey, tweak)

        assertEquals(expected.toHex(), actual.toHex())
    }

    @Test
    fun `d retornado por spendingPrivateKeyFromTweak produz exatamente o x-only pubkey do output`() {
        // Confere a ponte matemática que TxAssembler.deriveSilentPaymentSpendableInput
        // depende: d = spendingPrivateKeyFromTweak(...) tem que satisfazer
        // x(d·G) == x(P_k) — P_k calculado independentemente via
        // outputPublicKey (lado remetente), sem passar pela mesma função.
        val spendPrivKey = "1d37787c2b7116ee983e9f9c13269df29091b391c04db94239e0d2bc2182c3bf".hexToBytes()
        val spendPubKey  = Secp256k1.publicKeyFromPrivate(spendPrivKey)
        val sharedSecret = "028158aff7d61ea66b2fa7f555bc3c5937d1debbde16423d630f9aa7943e14d80d".hexToBytes()

        val pK = Bip352.outputPublicKey(spendPubKey, sharedSecret, 0)
        val expectedXOnly = pK.copyOfRange(1, 33)

        val tweak = Bip352.outputTweak(sharedSecret, 0)
        val d = Bip352.spendingPrivateKeyFromTweak(spendPrivKey, tweak)
        val actualXOnly = Secp256k1.xOnlyPublicKeyFromPrivate(d)

        assertEquals(expectedXOnly.toHex(), actualXOnly.toHex())
    }
}
