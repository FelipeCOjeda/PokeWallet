package com.pokewallet.crypto

import com.pokewallet.network.RemoteUtxo
import com.pokewallet.network.WalletScanner

/**
 * Decide QUAIS UTXOs entram numa tx e quanto vai pro destino/troco —
 * extraído de WalletViewModel.resolveSpend() (achado ALTO 11/12 da
 * auditoria: God Object sem teste cobrindo a lógica de gasto). Puro: só a
 * PARTE de decisão sai daqui — scan de rede (WalletScanner) e verificação
 * de valor contra a tx anterior (UtxoValueVerifier, também rede) continuam
 * no ViewModel, que chama estas funções entre os dois passos de rede.
 * Compartilhado pelos dois caminhos de envio (assinatura local via
 * [TxAssembler] e air-gapped via [PsbtAssembler]) — nenhum dos dois deriva
 * chave nenhuma aqui, isso é responsabilidade de cada um.
 */
object SpendResolver {

    /** Um UTXO candidato a ser gasto, com o endereço (chain/index) que o controla. */
    data class Candidate(val chain: Int, val index: Int, val utxo: RemoteUtxo)

    data class Resolved(
        val chosen: List<Candidate>,
        val destSpk: ByteArray,
        val sendAmount: Long,
        val changeValue: Long?
    )

    /** Endereços escaneados -> candidatos, com os UTXOs congelados (tela de
     *  UTXOs) já removidos — congelar promete proteção na UI, então tem
     *  que valer pra qualquer caminho que resolve um envio. */
    fun candidatesFrom(
        addressesWithFunds: List<WalletScanner.ScannedAddress>,
        frozenUtxoKeys: Set<String>
    ): List<Candidate> =
        addressesWithFunds.flatMap { addr ->
            addr.utxos
                .filterNot { frozenUtxoKeys.contains("${it.txid}:${it.vout}") }
                .map { Candidate(addr.chain, addr.index, it) }
        }

    /**
     * Escolhe quais candidatos entram na tx: seleção manual (Fase B3, usa
     * EXATAMENTE os UTXOs pedidos, nunca completa com outros) > sweep
     * (gasta tudo que não estiver congelado) > CoinSelector automático
     * (envio parcial de verdade, não consolida a carteira inteira).
     */
    fun chooseUtxos(
        candidates: List<Candidate>,
        amountSats: Long?,
        sweep: Boolean,
        manualUtxoKeys: Set<String>?,
        feeRateSatPerVbyte: Double,
        spendType: SpendType
    ): List<Candidate> {
        if (manualUtxoKeys != null) {
            val byKey = candidates.associateBy { "${it.utxo.txid}:${it.utxo.vout}" }
            val missing = manualUtxoKeys.filterNot { byKey.containsKey(it) }
            if (missing.isNotEmpty()) {
                error("Um ou mais UTXOs selecionados não estão mais disponíveis (gasto ou congelado nesse meio-tempo) — atualize a seleção e tente de novo.")
            }
            val manualChosen = manualUtxoKeys.map { byKey.getValue(it) }
            if (!sweep) {
                val targetValue = amountSats ?: error("Valor não informado")
                val totalSelected = manualChosen.sumOf { it.utxo.valueSats }
                val estimatedFee = FeeEstimator.estimateFee(manualChosen.size, 2, spendType, feeRateSatPerVbyte)
                require(totalSelected >= targetValue + estimatedFee) {
                    "Os UTXOs selecionados ($totalSelected sat) não cobrem o valor + taxa estimada (~${targetValue + estimatedFee} sat) — selecione mais UTXOs."
                }
            }
            return manualChosen
        }

        if (sweep) return candidates

        val targetValue = amountSats ?: error("Valor não informado")
        val coinUtxos = candidates.map { c ->
            Utxo(
                txid         = c.utxo.txid.hexToBytes(),
                vout         = c.utxo.vout,
                value        = c.utxo.valueSats,
                scriptPubKey = byteArrayOf(),
                chain        = c.chain,
                index        = c.index
            )
        }
        val (selected, _) = CoinSelector.select(coinUtxos, targetValue, feeRateSatPerVbyte, spendType)
        val selectedRefs = java.util.IdentityHashMap<Utxo, Unit>()
        selected.forEach { selectedRefs[it] = Unit }
        return candidates.filterIndexed { i, _ -> selectedRefs.containsKey(coinUtxos[i]) }
    }

    /**
     * Resolve o scriptPubKey de destino + valor de envio/troco a partir
     * dos UTXOs JÁ escolhidos (ver [chooseUtxos]) — puro, sem rede/IO.
     *
     * [precomputedDestSpk], quando presente, pula [AddressCodec.addressToScriptPubKey]
     * e usa esse valor direto — necessário pra endereço Silent Payments
     * (BIP-352): o scriptPubKey real de um output SP só pode ser calculado
     * por quem tem as chaves privadas dos inputs sendo gastos (precisa de
     * ECDH), então quem chama isso pra um destino SP já resolveu o script
     * ANTES de chegar aqui (ver WalletViewModel.resolveSpend /
     * TxAssembler.resolveSilentPaymentDestination) — SP não é um tipo de
     * script fixo decodificável só a partir do endereço.
     */
    fun resolve(
        chosen: List<Candidate>,
        destination: String,
        network: Network,
        amountSats: Long?,
        sweep: Boolean,
        feeRateSatPerVbyte: Double,
        spendType: SpendType,
        precomputedDestSpk: ByteArray? = null
    ): Resolved {
        val totalInputSats = chosen.sumOf { it.utxo.valueSats }
        val destSpk = precomputedDestSpk ?: AddressCodec.addressToScriptPubKey(destination, network)

        val plan = ChangePlanner.plan(
            totalInputSats     = totalInputSats,
            requestedAmount    = amountSats,
            sweep              = sweep,
            inputCount         = chosen.size,
            feeRateSatPerVbyte = feeRateSatPerVbyte,
            spendType          = spendType
        )

        return Resolved(chosen, destSpk, plan.sendAmount, plan.changeValue)
    }
}
