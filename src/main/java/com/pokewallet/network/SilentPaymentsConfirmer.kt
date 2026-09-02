package com.pokewallet.network

import com.pokewallet.crypto.Network
import com.pokewallet.crypto.SilentPaymentsScanner
import com.pokewallet.crypto.parseRawTx

/**
 * Confirma um [SilentPaymentsScanner.Candidate] contra uma fonte própria
 * (mesmo [ChainDataSource] já configurado no app — Electrum/Blockstream)
 * ANTES de considerá-lo um UTXO real. O "outputs_short" do oracle é só um
 * filtro probabilístico de 8 bytes (ver documentação do scanner) — esta
 * classe é o que transforma "parece que é nosso" em "É nosso, confirmado
 * pelos 32 bytes reais on-chain", igual [UtxoValueVerifier] faz pra UTXOs
 * normais antes de assinar.
 *
 * Busca a transação BRUTA por txid e recalcula o txid a partir dos bytes
 * recebidos (double-sha256, [parseRawTx]) — o provedor não consegue forjar
 * isso sem quebrar SHA-256. Confere TODOS os outputs da tx procurando o
 * scriptPubKey P2TR exato (`0x51 0x20` + x-only de 32 bytes) do candidato —
 * não confia no `vout` nenhum (o oracle não manda vout junto do short).
 */
object SilentPaymentsConfirmer {

    data class ConfirmedUtxo(
        val txid: String,
        val vout: Int,
        val valueSats: Long,
        val outputXOnlyPubKey: ByteArray,
        val tweak: ByteArray,
        val k: Int,
        val blockHeight: Long
    )

    /** null quando o candidato era um falso positivo do filtro de 8 bytes
     *  (esperado ser raríssimo — 1 em 2^64 — mas o código tem que aceitar
     *  isso como resultado normal, não como erro). */
    fun confirm(candidate: SilentPaymentsScanner.Candidate, dataSource: ChainDataSource, network: Network): ConfirmedUtxo? {
        val txidDisplay = candidate.txidLE.reversedArray().joinToString("") { "%02x".format(it) }

        val rawTxHex = dataSource.getRawTx(txidDisplay, network)
        val parsed = parseRawTx(rawTxHex)

        require(parsed.txid.equals(txidDisplay, ignoreCase = true)) {
            "O provedor de dados devolveu uma transação cujo txid recalculado " +
                "(${parsed.txid}) não bate com o pedido ($txidDisplay) — possível servidor " +
                "malicioso ou conexão adulterada. Candidato SP descartado por segurança."
        }

        val expectedScript = byteArrayOf(0x51, 0x20) + candidate.outputXOnlyPubKey
        val vout = parsed.outputs.indexOfFirst { it.scriptPubKey.contentEquals(expectedScript) }
        if (vout == -1) return null // falso positivo do filtro de 8 bytes

        return ConfirmedUtxo(
            txid              = txidDisplay,
            vout              = vout,
            valueSats         = parsed.outputs[vout].value,
            outputXOnlyPubKey = candidate.outputXOnlyPubKey,
            tweak             = candidate.tweak,
            k                 = candidate.k,
            blockHeight       = candidate.blockHeight
        )
    }
}
