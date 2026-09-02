package com.pokewallet.crypto

import com.pokewallet.network.BlindBitOracleClient

/**
 * Escaneia dados de bloco JÁ BAIXADOS (via [BlindBitOracleClient]) atrás de
 * outputs Silent Payments (BIP-352) endereçados a esta carteira — puro, sem
 * rede/IO, testável com dados sintéticos. Achado aqui é só um CANDIDATO: o
 * "outputs_short" do oracle é uma pubkey truncada de 8 bytes (filtro
 * probabilístico, colisão falsa possível embora rara — 1 em 2^64), NUNCA
 * prova de fundos. Confirmação contra os 32 bytes reais on-chain (via
 * qualquer [com.pokewallet.network.ChainDataSource] já configurado no app)
 * fica pro chamador — ver `SilentPaymentsConfirmer`.
 *
 * Sem suporte a labels (mesma decisão da Fase 0 — troco já usa BIP84/86
 * normal, não precisa de auto-pagamento SP).
 */
object SilentPaymentsScanner {

    /** Um output candidato — ainda NÃO confirmado contra uma fonte própria. */
    data class Candidate(
        val txidLE: ByteArray,
        /** x-only pubkey candidata (32 bytes) — o scriptPubKey P2TR real é
         *  `0x51 0x20` + isto. */
        val outputXOnlyPubKey: ByteArray,
        /** Índice usado nessa tx (0 pro caso comum de um único output nosso
         *  por tx; >0 só quando o mesmo remetente paga a mesma SP address
         *  mais de uma vez na mesma transação). */
        val k: Int,
        /** t_k — precisa disso depois pra montar a chave de gasto
         *  ([Bip352.spendingPrivateKey]), não recalculável só a partir do
         *  outpoint depois que o bloco não está mais em mãos. */
        val tweak: ByteArray,
        val blockHeight: Long
    )

    /**
     * Escaneia UM bloco — tenta k=0,1,2,... por transação elegível, parando
     * no primeiro k SEM match (heurística padrão de qualquer client
     * BIP-352: outputs pro mesmo destinatário na mesma tx usam k crescente
     * sem buraco, então parar no primeiro miss não perde nenhum real).
     */
    fun scanBlock(
        block: BlindBitOracleClient.BlockScanData,
        scanPrivateKey: ByteArray,
        spendPubKey: ByteArray
    ): List<Candidate> {
        val found = mutableListOf<Candidate>()
        for (tx in block.txs) {
            val sharedSecret = Bip352.receiverSharedSecretFromPrecomputedTweak(scanPrivateKey, tx.tweak)
            var k = 0
            while (true) {
                val candidateOutputKey = Bip352.outputPublicKey(spendPubKey, sharedSecret, k)
                val candidateXOnly = candidateOutputKey.copyOfRange(1, 33)
                val short = candidateXOnly.copyOfRange(0, 8)
                val matches = tx.outputsShort.any { it.contentEquals(short) }
                if (!matches) break
                found += Candidate(
                    txidLE            = tx.txidLE,
                    outputXOnlyPubKey = candidateXOnly,
                    k                 = k,
                    tweak             = Bip352.outputTweak(sharedSecret, k),
                    blockHeight       = block.blockHeight
                )
                k++
            }
        }
        return found
    }
}
