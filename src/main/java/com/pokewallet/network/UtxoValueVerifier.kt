package com.pokewallet.network

import com.pokewallet.crypto.Network
import com.pokewallet.crypto.parseRawTx

/**
 * Confere, ANTES de assinar, que o valor de um [RemoteUtxo] reportado por
 * um [ChainDataSource] bate com a transação anterior real.
 *
 * O sighash SegWit/Taproot (BIP143/BIP341) compromete o valor DECLARADO do
 * input pro cálculo da assinatura, não o valor real gasto — um provedor
 * malicioso ou um MITM (ex.: Electrum sem TLS numa rede não confiável)
 * pode reportar um valor MENOR que o real. A wallet assinaria de boa fé, e
 * a diferença entre o valor real e o declarado vira fee pro minerador sem
 * qualquer aviso ao usuário.
 *
 * A defesa: buscar a transação anterior completa por txid e recalcular o
 * txid a partir dos bytes recebidos (double-sha256, ver [parseRawTx]) —
 * como o txid É o hash da própria tx, o provedor não consegue forjar um
 * valor de output diferente do real sem quebrar SHA-256. Equivalente ao
 * que uma hardware wallet faz ao exigir `nonWitnessUtxo` num PSBT. Não é
 * uma prova SPV completa (não confirma que a tx foi minerada/está no
 * melhor branch) — só que o CONTEÚDO reportado pro UTXO é genuíno, o que
 * já fecha o vetor de fee inflado.
 */
object UtxoValueVerifier {

    class UtxoMismatchException(message: String) : Exception(message)

    fun verify(dataSource: ChainDataSource, network: Network, utxo: RemoteUtxo) {
        val rawTxHex = try {
            dataSource.getRawTx(utxo.txid, network)
        } catch (e: Exception) {
            throw UtxoMismatchException(
                "Não foi possível confirmar a transação de origem do UTXO " +
                    "${utxo.txid}:${utxo.vout} antes de assinar — ${e.message}"
            )
        }

        val parsed = try {
            parseRawTx(rawTxHex)
        } catch (e: Exception) {
            throw UtxoMismatchException(
                "Transação de origem do UTXO ${utxo.txid}:${utxo.vout} veio corrompida " +
                    "ou em formato inesperado do provedor de dados — ${e.message}"
            )
        }

        if (!parsed.txid.equals(utxo.txid, ignoreCase = true)) {
            throw UtxoMismatchException(
                "O provedor de dados devolveu uma transação cujo txid recalculado " +
                    "(${parsed.txid}) não bate com o esperado (${utxo.txid}) — possível " +
                    "servidor malicioso ou conexão adulterada. Envio cancelado por segurança."
            )
        }

        if (utxo.vout !in parsed.outputs.indices) {
            throw UtxoMismatchException(
                "A transação de origem do UTXO ${utxo.txid}:${utxo.vout} não tem essa saída."
            )
        }

        val realValue = parsed.outputs[utxo.vout].value
        if (realValue != utxo.valueSats) {
            throw UtxoMismatchException(
                "O valor do UTXO ${utxo.txid}:${utxo.vout} reportado pelo servidor " +
                    "(${utxo.valueSats} sat) não bate com o valor real on-chain " +
                    "($realValue sat) — possível tentativa de inflar a taxa às suas custas. " +
                    "Envio cancelado por segurança."
            )
        }
    }
}
