package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Valida TaprootSighashCalculator contra o vetor de teste OFICIAL do
 * BIP341 (bitcoin/bips, wallet-test-vectors.json, keyPathSpending[0],
 * inputSpending com txinIndex=4, hashType=0/SIGHASH_DEFAULT).
 *
 * Esse vetor específico existe porque a primeira versão desta classe
 * (antes desta correção) tinha múltiplos desvios da spec — double-SHA256
 * em vez de SHA256 simples, hash_type serializado como 4 bytes em vez de
 * 1, e os campos hashAnnex/keyVersion/codesepPos incluídos incondicional-
 * mente quando deveriam estar AUSENTES sem annex/script-path — o que
 * fazia toda assinatura Taproot (BIP86) produzida pelo app ser inválida e
 * rejeitada pela rede. A matemática do tweak (TaprootTweakTest.kt) já
 * era validada; o sighash em si nunca tinha sido, e é aqui que o bug
 * estava.
 */
class TaprootSighashCalculatorTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    @Test
    fun `sighash matches official BIP341 vector (txinIndex 4, SIGHASH_DEFAULT)`() {
        val rawUnsignedTx = hex(
            "02000000097de20cbff686da83a54981d2b9bab3586f4ca7e48f57f5b55963115f3b334e9c" +
            "010000000000000000d7b7cab57b1393ace2d064f4d4a2cb8af6def61273e127517d44759b6dafdd99" +
            "0000000000fffffffff8e1f583384333689228c5d28eac13366be082dc57441760d957275419a418420000000000fffffffff0689180aa63b30cb162a73c6d2a38b7eeda2a83ece74310fda0843ad604853b0100000000feffffffaa5202bdf6d8ccd2ee0f0202afbbb7461d9264a25e5bfd3c5a52ee1239e0ba6c0000000000feffffff956149bdc66faa968eb2be2d2faa29718acbfe3941215893a2a3446d32acd050000000000000000000e664b9773b88c09c32cb70a2a3e4da0ced63b7ba3b22f848531bbb1d5d5f4c94010000000000000000e9aa6b8e6c9de67619e6a3924ae25696bb7b694bb677a632a74ef7eadfd4eabf0000000000ffffffffa778eb6a263dc090464cd125c466b5a99667720b1c110468831d058aa1b82af10100000000ffffffff0200ca9a3b000000001976a91406afd46bcdfd22ef94ac122aa11f241244a37ecc88ac807840cb0000000020ac9a87f5594be208f8532db38cff670c450ed2fea8fcdefcc9a663f78bab962b0065cd1d"
        )
        val unsignedTx = UnsignedTransaction.parse(rawUnsignedTx)

        val utxos = listOf(
            TxOut(420000000L, hex("512053a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343")),
            TxOut(462000000L, hex("5120147c9c57132f6e7ecddba9800bb0c4449251c92a1e60371ee77557b6620f3ea3")),
            TxOut(294000000L, hex("76a914751e76e8199196d454941c45d1b3a323f1433bd688ac")),
            TxOut(504000000L, hex("5120e4d810fd50586274face62b8a807eb9719cef49c04177cc6b76a9a4251d5450e")),
            TxOut(630000000L, hex("512091b64d5324723a985170e4dc5a0f84c041804f2cd12660fa5dec09fc21783605")),
            TxOut(378000000L, hex("00147dd65592d0ab2fe0d0257d571abf032cd9db93dc")),
            TxOut(672000000L, hex("512075169f4001aa68f15bbed28b218df1d0a62cbbcf1188c6665110c293c907b831")),
            TxOut(546000000L, hex("5120712447206d7a5238acc7ff53fbe94a3b64539ad291c7cdbc490b7577e4b17df5")),
            TxOut(588000000L, hex("512077e30a5522dd9f894c3f8b8bd4c4b2cf82ca7da8a3ea6a239655c39c050ab220"))
        )

        val expectedSighash = "4f900a0bae3f1446fd48490c2958b5a023228f01661cda3496a11da502a7f7ef"

        val sighash = TaprootSighashCalculator.calculate(
            tx = unsignedTx,
            inputIndex = 4,
            utxos = utxos
        )

        assertEquals(expectedSighash, sighash.toHex())
    }
}
