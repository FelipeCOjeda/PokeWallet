package com.pokewallet.crypto

import com.pokewallet.network.RemoteUtxo
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Teste de ponta a ponta do fluxo de envio (achado ALTO 11/12 da
 * auditoria: WalletViewModel era um God Object sem NENHUM teste cobrindo
 * o código que efetivamente move fundos) — cobre juntas as quatro classes
 * extraídas dele (SpendResolver, TxAssembler, PsbtAssembler,
 * AirGappedPsbtSigner), do jeito que elas realmente são encadeadas em
 * produção, sem precisar de device/rede/Keystore.
 *
 * A checagem principal: os DOIS caminhos de envio que o app oferece —
 * assinatura local (carteira com seed, TxAssembler) e air-gapped (watch-
 * only monta com PsbtAssembler, aparelho separado assina com
 * AirGappedPsbtSigner) — partindo dos MESMOS UTXOs e da MESMA seed, têm
 * que produzir a MESMA transação assinada byte-a-byte. ECDSA/Schnorr aqui
 * são determinísticos (mesma privkey+mensagem = mesma assinatura sempre),
 * então essa igualdade é uma prova forte de que as duas montagens
 * concordam em tudo: qual UTXO é de qual endereço, qual chave assina qual
 * input, ordem dos outputs, cálculo de troco.
 */
class AirGappedRoundTripTest {

    private val seed = ByteArray(32) { (it + 1).toByte() }
    private val network = Network.TESTNET
    private val masterFingerprint = ByteArray(4) { 0x11 }

    private fun accountXpub(spendType: SpendType): String {
        val purpose = spendType.bipPurpose()
        val accountKey = KeyDerivation.derive(
            seed,
            intArrayOf(KeyDerivation.hardened(purpose), KeyDerivation.hardened(network.coinType), KeyDerivation.hardened(0))
        )
        return XpubEncoder.encode(accountKey, network)
    }

    private fun fakeUtxo(txidFiller: Byte, vout: Int, valueSats: Long) = RemoteUtxo(
        txid        = ByteArray(32) { txidFiller }.toHex(),
        vout        = vout,
        valueSats   = valueSats,
        confirmed   = true,
        blockHeight = 800_000
    )

    private fun destinationAddress(): String =
        AddressBuilder.p2wpkh(Hashes.hash160("destino de teste - nao eh chave real".toByteArray()), network)

    private fun watchOnlyWalletData(spendType: SpendType, xpub: String) = WalletData(
        walletName             = "teste",
        mnemonic                = null,
        passphrase              = null,
        mnemonicVerified        = true,
        isWatchOnly             = false, // o SIGNER tem seed — quem é watch-only é o outro aparelho, não modelado aqui
        fingerprint             = masterFingerprint.toHex(),
        hasVerifiedFingerprint  = true,
        network                 = network,
        spendType               = spendType,
        xpub                    = xpub,
        accountOrigin           = null,
        nextExternalIndex       = 0,
        nextInternalIndex       = 0,
        activeExternalIndices   = emptySet(),
        activeInternalIndices   = emptySet(),
        needsFullRescan         = false,
        frozenUtxoKeys          = emptySet(),
        raw                     = JSONObject()
    )

    private fun runRoundTrip(spendType: SpendType) {
        val xpub = accountXpub(spendType)
        val candidates = listOf(
            SpendResolver.Candidate(chain = 0, index = 0, utxo = fakeUtxo(0x01, 0, 50_000L)),
            SpendResolver.Candidate(chain = 0, index = 1, utxo = fakeUtxo(0x02, 0, 30_000L))
        )
        val destination = destinationAddress()

        // ── Caminho 1: assinatura LOCAL (carteira com seed neste aparelho) ──
        val chosenLocal   = SpendResolver.chooseUtxos(candidates, 60_000L, sweep = false, manualUtxoKeys = null, feeRateSatPerVbyte = 1.0, spendType = spendType)
        val resolvedLocal = SpendResolver.resolve(chosenLocal, destination, network, 60_000L, sweep = false, feeRateSatPerVbyte = 1.0, spendType = spendType)
        val spendable     = TxAssembler.deriveSpendableInputs(resolvedLocal.chosen, seed, network, spendType)
        val localChangeValue = resolvedLocal.changeValue
        val outputsLocal: List<TxOut> = if (localChangeValue != null) {
            val (changePriv, _, changeSpk) = TxAssembler.deriveKeyAndScript(seed, network, spendType, 1, 0)
            changePriv.fill(0)
            listOf(TxOut(resolvedLocal.sendAmount, resolvedLocal.destSpk), TxOut(localChangeValue, changeSpk))
        } else {
            listOf(TxOut(resolvedLocal.sendAmount, resolvedLocal.destSpk))
        }
        val (rawTxLocal, txidLocal) = TxAssembler.signAndFinalize(spendable, outputsLocal, spendType)

        // ── Caminho 2: AIR-GAPPED (watch-only monta -> aparelho signer assina) ──
        val chosenAirGapped   = SpendResolver.chooseUtxos(candidates, 60_000L, sweep = false, manualUtxoKeys = null, feeRateSatPerVbyte = 1.0, spendType = spendType)
        val resolvedAirGapped = SpendResolver.resolve(chosenAirGapped, destination, network, 60_000L, sweep = false, feeRateSatPerVbyte = 1.0, spendType = spendType)
        val changeIndex = if (resolvedAirGapped.changeValue != null) 0 else null
        val built = PsbtAssembler.build(
            chosen            = resolvedAirGapped.chosen,
            destSpk           = resolvedAirGapped.destSpk,
            sendAmount        = resolvedAirGapped.sendAmount,
            changeValue       = resolvedAirGapped.changeValue,
            changeIndex       = changeIndex,
            xpub              = xpub,
            network           = network,
            spendType         = spendType,
            masterFingerprint = masterFingerprint
        )
        val wallet = watchOnlyWalletData(spendType, xpub)
        val (rawTxAirGappedHex, txidAirGapped) = when (spendType) {
            SpendType.BIP84 -> AirGappedPsbtSigner.signSegwit(built.psbtBase64, seed, wallet, network)
            SpendType.BIP86 -> AirGappedPsbtSigner.signTaproot(built.psbtBase64, seed, wallet, network)
        }

        assertEquals("expectedTxid do PsbtAssembler tem que bater com o txid computado depois de assinar", built.expectedTxid, txidAirGapped)
        assertEquals("os dois caminhos (local e air-gapped) tem que chegar no MESMO txid pros MESMOS UTXOs", txidLocal, txidAirGapped)
        assertArrayEquals(
            "os dois caminhos tem que produzir a MESMA tx assinada byte-a-byte (ECDSA/Schnorr determinísticos aqui)",
            rawTxLocal, rawTxAirGappedHex.hexToBytes()
        )
    }

    @Test
    fun `round trip BIP84 (SegWit v0) - assinatura local e air-gapped chegam no mesmo resultado`() {
        runRoundTrip(SpendType.BIP84)
    }

    @Test
    fun `round trip BIP86 (Taproot) - assinatura local e air-gapped chegam no mesmo resultado`() {
        runRoundTrip(SpendType.BIP86)
    }

    @Test
    fun `round trip sem troco (sweep) - continua batendo entre os dois caminhos`() {
        val spendType = SpendType.BIP84
        val xpub = accountXpub(spendType)
        val candidates = listOf(SpendResolver.Candidate(chain = 0, index = 0, utxo = fakeUtxo(0x03, 0, 20_000L)))
        val destination = destinationAddress()

        val chosen1 = SpendResolver.chooseUtxos(candidates, amountSats = null, sweep = true, manualUtxoKeys = null, feeRateSatPerVbyte = 1.0, spendType = spendType)
        val resolved1 = SpendResolver.resolve(chosen1, destination, network, amountSats = null, sweep = true, feeRateSatPerVbyte = 1.0, spendType = spendType)
        assert(resolved1.changeValue == null) { "sweep não deveria gerar troco" }
        val spendable1 = TxAssembler.deriveSpendableInputs(resolved1.chosen, seed, network, spendType)
        val (rawTxLocal, txidLocal) = TxAssembler.signAndFinalize(spendable1, listOf(TxOut(resolved1.sendAmount, resolved1.destSpk)), spendType)

        val chosen2 = SpendResolver.chooseUtxos(candidates, amountSats = null, sweep = true, manualUtxoKeys = null, feeRateSatPerVbyte = 1.0, spendType = spendType)
        val resolved2 = SpendResolver.resolve(chosen2, destination, network, amountSats = null, sweep = true, feeRateSatPerVbyte = 1.0, spendType = spendType)
        val built = PsbtAssembler.build(
            chosen = resolved2.chosen, destSpk = resolved2.destSpk, sendAmount = resolved2.sendAmount,
            changeValue = resolved2.changeValue, changeIndex = null, xpub = xpub, network = network,
            spendType = spendType, masterFingerprint = masterFingerprint
        )
        val wallet = watchOnlyWalletData(spendType, xpub)
        val (rawTxAirGappedHex, txidAirGapped) = AirGappedPsbtSigner.signSegwit(built.psbtBase64, seed, wallet, network)

        assertEquals(txidLocal, txidAirGapped)
        assertArrayEquals(rawTxLocal, rawTxAirGappedHex.hexToBytes())
    }
}
