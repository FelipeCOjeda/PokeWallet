package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vetor de teste OFICIAL do BIP143 ("Native P2WPKH"), baixado direto de
 * github.com/bitcoin/bips/blob/master/bip-0143.mediawiki (repo bitcoin/bips) —
 * valida o SegwitSigner (hashPrevouts/hashSequence/hashOutputs + assinatura
 * ECDSA determinística RFC6979) ponta a ponta contra a spec oficial, não só
 * a consistência interna do próprio código.
 */
class SegwitSignerBip143Test {

    @Test
    fun signsNativeP2wpkhVectorExactly() {
        val tx = UnsignedTransaction(
            version = 1,
            inputs = listOf(
                TxIn(
                    prevTxId  = "fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f".hexToBytes(),
                    prevIndex = 0,
                    scriptSig = byteArrayOf(),
                    // Bytes crus no wire (LE) são "eeffffff"; como inteiro (o
                    // que TxIn.sequence/int32LE espera) isso é 0xffffffee, não
                    // 0xeeffffff — a ordem dos bytes não pode ser lida direto.
                    sequence  = 0xffffffeeL
                ),
                TxIn(
                    prevTxId  = "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a".hexToBytes(),
                    prevIndex = 1,
                    scriptSig = byteArrayOf(),
                    sequence  = 0xffffffffL
                )
            ),
            outputs = listOf(
                TxOut(112340000L, "76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac".hexToBytes()),
                TxOut(223450000L, "76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac".hexToBytes())
            ),
            lockTime = 17L
        )

        // Segundo input (index 1) é o P2WPKH sendo assinado, value 6 BTC.
        val privateKey   = "619c335025c7f4012e556c2a58b2506e30b8511b53ade95ea316fd8c3286feb9".hexToBytes()
        val scriptPubKey = "00141d0f172a0ecb48aee1be1f2687d2963ae33f71a1".hexToBytes()

        val sig = SegwitSigner.sign(
            unsignedTx   = tx,
            inputIndex   = 1,
            utxoValue    = 600000000L,
            scriptPubKey = scriptPubKey,
            privateKey   = privateKey
        )

        val expected = "304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a0220573a954c4518331561406f90300e8f3358f51928d43c212a8caed02de67eebee01"
        assertEquals(expected, sig.toHex())
    }
}
