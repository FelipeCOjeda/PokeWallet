package com.pokewallet.lightning

import android.content.Context
import breez_sdk_spark.BreezSdk
import breez_sdk_spark.Config
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.EventListener
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.InputType
import breez_sdk_spark.LnurlPayRequest
import breez_sdk_spark.OnchainConfirmationSpeed
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PrepareLnurlPayRequest
import breez_sdk_spark.PrepareLnurlPayResponse
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.connect as sdkConnect
import breez_sdk_spark.defaultConfig
import com.pokewallet.BuildConfig
import com.pokewallet.crypto.Network as WalletNetwork
import java.math.BigInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Wrapper fino sobre a Breez SDK - Spark (pagamentos Lightning
 * self-custodial: BOLT11, Spark address, LN address, e SAQUE pro on-chain
 * — enviar pra um endereço Bitcoin normal, ex.: o endereço de recebimento
 * desta própria carteira fora da Lightning, é só mais um destino aceito
 * por [prepareSend]/[confirmSend], sem função separada). Isolado de
 * propósito do resto do app: nada aqui mexe em wallet.json, UtxoProvider,
 * BalanceService ou qualquer caminho on-chain diretamente — o saldo
 * Lightning é lido só sob demanda via [balanceSats] e NUNCA somado ao
 * saldo on-chain em nenhum ponto deste arquivo (quem soma pra exibição,
 * ver WalletFragment.updateTotalBalanceDisplay, soma fora daqui).
 *
 * Uma instância = uma conexão ativa com o SDK pra UMA carteira. Feature
 * opt-in: só existe uma instância quando o usuário pede pra usar Lightning
 * (não conecta automaticamente ao abrir o app).
 */
class LightningWallet private constructor(private val sdk: BreezSdk) {

    suspend fun disconnect() {
        sdk.disconnect()
    }

    /** Saldo Lightning (Spark) em satoshis. [ensureSynced] força uma
     *  sincronização antes de ler — mais lento, mais confiável. */
    suspend fun balanceSats(ensureSynced: Boolean = true): Long =
        sdk.getInfo(GetInfoRequest(ensureSynced)).balanceSats.toLong()

    /** Gera um invoice BOLT11 pra receber. [amountSats] null = invoice sem
     *  valor fixo (quem paga escolhe quanto). */
    suspend fun receiveBolt11(amountSats: Long?, description: String): String {
        val method = ReceivePaymentMethod.Bolt11Invoice(
            description = description,
            amountSats = (amountSats ?: 0L).toULong(),
            expirySecs = DEFAULT_INVOICE_EXPIRY_SECS,
            paymentHash = null,
            receiverIdentityPublicKey = null,
        )
        return sdk.receivePayment(ReceivePaymentRequest(method)).paymentRequest
    }

    /** Endereço Spark próprio (reutilizável, sem expiração) — mais barato
     *  que BOLT11 quando quem paga também usa Spark/Breez. */
    suspend fun receiveSparkAddress(): String =
        sdk.receivePayment(ReceivePaymentRequest(ReceivePaymentMethod.SparkAddress)).paymentRequest

    /**
     * Endereço de DEPÓSITO on-chain (peg-in: BTC on-chain vira saldo
     * Lightning/Spark depois de confirmar na rede + a SDK "reivindicar" o
     * depósito automaticamente em segundo plano). SEMPRE o mesmo endereço
     * (`newAddress = false`) — pedido explícito do Felipe: gerar um
     * endereço novo a cada depósito arrisca espalhar valores pequenos por
     * vários endereços de depósito diferentes, e se algum ficar esquecido
     * (não reivindicado) esse valor fica preso lá, efetivamente perdido de
     * vista. Um endereço fixo é trivial de conferir/rastrear sempre.
     */
    suspend fun receiveOnchainDepositAddress(): String =
        sdk.receivePayment(ReceivePaymentRequest(ReceivePaymentMethod.BitcoinAddress(newAddress = false))).paymentRequest

    /** Interpreta texto colado/escaneado (invoice, endereço Spark, LN
     *  address, LNURL, etc.) antes de mandar pro fluxo de envio — mesmo
     *  papel que [com.pokewallet.crypto.SpendResolver] tem no on-chain. */
    suspend fun parseInput(raw: String): InputType =
        sdk.parse(raw.trim())

    /**
     * Fase 1 do envio: valida o destino e calcula taxa estimada, sem mover
     * fundos ainda. [amountSatsOverride] só é necessário quando o destino
     * não fixa valor (invoice amountless, endereço Spark, LN address).
     *
     * LN address e LNURL-pay usam um protocolo HTTP separado (LNURL) da SDK
     * — achado real testando no aparelho: mandar um LN address direto pelo
     * caminho normal ([PrepareSendPaymentRequest]/`prepareSendPayment`) dá
     * erro "unsupported payment method" da própria SDK. Por isso [parseInput]
     * roda primeiro pra decidir qual dos dois caminhos usar — a UI não
     * precisa saber dessa diferença, só recebe [PreparedLightningPayment].
     */
    suspend fun prepareSend(raw: String, amountSatsOverride: Long? = null): PreparedLightningPayment {
        val trimmed = raw.trim()
        val lnurlPayDetails = when (val parsed = runCatching { sdk.parse(trimmed) }.getOrNull()) {
            is InputType.LightningAddress -> parsed.v1.payRequest
            is InputType.LnurlPay -> parsed.v1
            else -> null
        }

        if (lnurlPayDetails != null) {
            val amount = amountSatsOverride?.let { BigInteger.valueOf(it) }
                ?: throw IllegalArgumentException("Informe o valor em sats — LN address não tem valor fixo.")
            val response = sdk.prepareLnurlPay(
                PrepareLnurlPayRequest(
                    amount = amount,
                    payRequest = lnurlPayDetails,
                    comment = null,
                    validateSuccessActionUrl = null,
                    tokenIdentifier = null,
                    conversionOptions = null,
                    feePolicy = null,
                )
            )
            return PreparedLightningPayment.Lnurl(response)
        }

        val request = PrepareSendPaymentRequest(
            paymentRequest = PaymentRequest.Input(trimmed),
            amount = amountSatsOverride?.let { BigInteger.valueOf(it) },
            tokenIdentifier = null,
            conversionOptions = null,
            feePolicy = null,
        )
        return PreparedLightningPayment.Standard(sdk.prepareSendPayment(request))
    }

    /**
     * Prepara o saque de TODO o saldo Lightning pro on-chain (peg-out "sacar
     * tudo" — um toque, sem o usuário escolher valor). A taxa NÃO é
     * subtraída automaticamente do `amount` pedido (`amount` + taxa juntos
     * precisam caber no saldo) — pedir `amount = saldo cheio` direto falharia
     * por saldo insuficiente assim que a taxa entrasse na conta. Por isso
     * dois passos: 1) uma pré-cotação com o saldo cheio só pra descobrir a
     * taxa (prepare não move fundo nenhum, então não tem custo repetir),
     * 2) a preparação de verdade com `amount = saldo - taxa`, que aí sim
     * cabe exatamente no saldo disponível.
     */
    suspend fun prepareSweepToOnchain(fixedAddress: String): PreparedLightningPayment {
        val balance = balanceSats(ensureSynced = false)
        require(balance > 0) { "Sem saldo Lightning pra sacar." }
        val feeProbe = prepareSend(fixedAddress, balance)
        val netAmount = balance - previewOf(feeProbe).feeSats
        require(netAmount > 0) { "Saldo Lightning insuficiente pra cobrir a taxa do saque." }
        return prepareSend(fixedAddress, netAmount)
    }

    /**
     * Fase 2 do envio: confirma e efetivamente move os fundos, a partir do
     * resultado de [prepareSend] que o usuário já viu/confirmou. Saque pro
     * on-chain (destino = endereço Bitcoin normal, ex.: o próprio endereço
     * de recebimento desta carteira fora da Lightning) é só mais um
     * [SendPaymentMethod] dentro do caminho [PreparedLightningPayment.Standard]
     * — a SDK resolve sozinha, só precisa da velocidade de confirmação em
     * [SendPaymentOptions] (aqui sempre [OnchainConfirmationSpeed.MEDIUM],
     * não exposto na UI ainda — ver pendência no SESSAO_ATUAL.txt).
     */
    suspend fun confirmSend(prepared: PreparedLightningPayment): Payment = when (prepared) {
        is PreparedLightningPayment.Standard -> {
            val options = if (prepared.response.paymentMethod is SendPaymentMethod.BitcoinAddress)
                SendPaymentOptions.BitcoinAddress(OnchainConfirmationSpeed.MEDIUM)
            else null
            sdk.sendPayment(SendPaymentRequest(prepared.response, options = options, idempotencyKey = null)).payment
        }
        is PreparedLightningPayment.Lnurl ->
            sdk.lnurlPay(LnurlPayRequest(prepared.response, idempotencyKey = null)).payment
    }

    /** Flow de eventos do SDK (sync concluído, pagamento recebido/enviado/
     *  falhou, depósito on-chain detectado, etc.) — cancelar a coleta
     *  desregistra o listener automaticamente. */
    fun events(): Flow<SdkEvent> = callbackFlow {
        val listener = object : EventListener {
            override suspend fun onEvent(event: SdkEvent) {
                trySend(event)
            }
        }
        val listenerId = sdk.addEventListener(listener)
        awaitClose {
            // Cleanup roda fora do escopo do callbackFlow (que já está
            // fechando/cancelando aqui) — precisa de um escopo próprio pra
            // não ser cancelado antes de completar.
            CoroutineScope(Dispatchers.IO).launch {
                sdk.removeEventListener(listenerId)
            }
        }
    }

    companion object {
        private const val DEFAULT_INVOICE_EXPIRY_SECS: UInt = 3600u

        /** Mínimo pra peg-in/peg-out (depósito/saque entre on-chain e
         *  Lightning) — pedido explícito do Felipe: valores menores fazem a
         *  taxa (rede + protocolo Spark) comer uma fatia grande demais do
         *  valor. Não se aplica a pagamentos Lightning normais (invoice,
         *  Spark address, LN address), só à transferência entre os dois
         *  saldos — ver [WalletFragment.showLightningPegDialog]. */
        const val MIN_PEG_AMOUNT_SATS = 4000L

        /**
         * Conecta ao Spark usando o MESMO seed BIP39 (mnemônico + passphrase)
         * da carteira on-chain — Spark deriva suas próprias chaves
         * internamente a partir do seed, não há reuso de endereço/chave com
         * o lado Bitcoin normal desta carteira.
         *
         * @throws IllegalArgumentException se [network] não for suportada
         *   pela Spark (ver [supportsLightning]).
         */
        suspend fun connect(
            context: Context,
            walletId: String,
            mnemonic: List<String>,
            passphrase: String,
            network: WalletNetwork,
        ): LightningWallet {
            val sparkNetwork = network.toSparkNetworkOrNull()
                ?: throw IllegalArgumentException("Rede $network não suporta Lightning/Spark")

            val config = defaultConfig(sparkNetwork).apply {
                apiKey = BuildConfig.BREEZ_API_KEY
            }
            val seed = Seed.Mnemonic(mnemonic.joinToString(" "), passphrase)
            val storageDir = LightningStorage.storageDir(context.filesDir, walletId).absolutePath

            val sdk = sdkConnect(ConnectRequest(config, seed, storageDir))
            return LightningWallet(sdk)
        }
    }
}

/** Resultado de [LightningWallet.prepareSend] — dois caminhos BEM
 *  diferentes por baixo da SDK (destino normal via `prepareSendPayment`,
 *  ou LN address/LNURL-pay via `prepareLnurlPay`, protocolo HTTP à parte),
 *  unificados aqui pra UI e o resto do ViewModel não precisarem conhecer
 *  os dois tipos de resposta da SDK nem decidir qual `confirm*` chamar. */
sealed class PreparedLightningPayment {
    data class Standard(val response: PrepareSendPaymentResponse) : PreparedLightningPayment()
    data class Lnurl(val response: PrepareLnurlPayResponse) : PreparedLightningPayment()
}

/** Resumo pronto pra UI do resultado de [LightningWallet.prepareSend] —
 *  valor e taxa vêm em formato diferente em cada um dos dois caminhos
 *  (espalhados dentro de [PrepareSendPaymentResponse.paymentMethod] no
 *  caminho normal, já prontos e planos em [PrepareLnurlPayResponse] no
 *  caminho LNURL-pay) — centraliza aqui em vez de espalhar esse `when`
 *  pela UI. [amountSats] null = destino não fixa valor e nenhum valor foi
 *  informado (não deveria acontecer — [LightningWallet.prepareSend] com
 *  `amountSatsOverride` já teria falhado antes de chegar aqui). */
data class SendPreview(val amountSats: Long?, val feeSats: Long)

fun previewOf(prepared: PreparedLightningPayment): SendPreview = when (prepared) {
    is PreparedLightningPayment.Lnurl -> SendPreview(
        amountSats = prepared.response.amountSats.toLong().takeIf { it > 0 },
        feeSats     = prepared.response.feeSats.toLong(),
    )
    is PreparedLightningPayment.Standard -> {
        val method = prepared.response.paymentMethod
        val amountSats = prepared.response.amount?.toLong()
            ?: (method as? SendPaymentMethod.Bolt11Invoice)?.invoiceDetails?.amountMsat?.toLong()?.div(1000L)
        val feeSats = when (method) {
            is SendPaymentMethod.Bolt11Invoice ->
                method.lightningFeeSats.toLong() + (method.sparkTransferFeeSats?.toLong() ?: 0L)
            is SendPaymentMethod.SparkAddress -> method.fee.toLong()
            // Saque pro on-chain — preview usa a mesma velocidade (MEDIUM)
            // que confirmSend() de fato usa, senão o valor mostrado aqui
            // não bateria com a taxa realmente cobrada.
            is SendPaymentMethod.BitcoinAddress ->
                method.feeQuote.speedMedium.userFeeSat.toLong() + method.feeQuote.speedMedium.l1BroadcastFeeSat.toLong()
            else -> 0L
        }
        SendPreview(amountSats?.takeIf { it > 0 }, feeSats)
    }
}
