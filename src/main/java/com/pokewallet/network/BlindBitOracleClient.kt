package com.pokewallet.network

import blindbit.oracle.v1.OracleServiceClient
import blindbit.oracle.v1.RangedBlockHeightRequestFiltered
import com.squareup.wire.GrpcClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Cliente gRPC pro blindbit-oracle (setavenger/blindbit-oracle — indexador
 * BIP-352 Silent Payments), usado pro scan de recebimento (Fase 3 do plano
 * de Silent Payments). Stubs gerados pelo plugin Wire a partir de
 * `src/main/proto/{oracle_service,indexing_server}.proto` (copiados sem
 * modificação de `setavenger/blindbit-rs` — ver README ali pro commit de
 * origem). Wire em vez do gRPC-Java oficial: roda sobre o OkHttp que o
 * projeto já usa, código gerado bem mais enxuto.
 *
 * URL pública confirmada direto do código-fonte do `blindbit-desktop`
 * (`internal/configs/defaults.go`) E testada ao vivo nesta sessão (ALPN
 * "h2" na porta 443 dos dois hosts): `oracle.setor.dev` (mainnet) e
 * `signet.oracle.setor.dev` (signet) — ver [com.pokewallet.android.BlindBitOraclePrefs].
 *
 * Convenções de byte do próprio oracle (`internal/server/GRPC.md` do
 * repo): block hash e txid vêm em LITTLE-ENDIAN — já no formato interno
 * que esta wallet usa em outpoint/txidLE em toda parte, SEM reversão
 * aqui (só reverte na hora de EXIBIR pro usuário, como em qualquer outro
 * lugar do app). "Tweak" é uma pubkey comprimida de 33 bytes — o servidor
 * já pré-multiplica `input_hash · A` (soma das pubkeys dos inputs
 * elegíveis) do lado dele, o que evita o cliente precisar buscar/parsear
 * os inputs de cada tx: o destinatário só precisa multiplicar esse ponto
 * pela própria scan private key pra chegar no shared secret (equivalente
 * matemático de [com.pokewallet.crypto.Bip352.receiverSharedSecret], só
 * que sem precisar conhecer `A`/outpoint_L separadamente — a associação
 * escalar é a mesma: `b_scan·(h·A) == (b_scan·h)·A`). "Shortened pubkey"
 * (8 bytes) é só um filtro PROBABILÍSTICO local pra decidir quais txs
 * merecem o cálculo completo — nunca prova de fundos: o scanner precisa
 * confirmar contra os 32 bytes reais (ex. via Electrum/Blockstream já
 * configurados no app) antes de considerar um UTXO como real.
 */
object BlindBitOracleClient {

    data class OracleInfo(
        val network: String,
        val height: Long,
        val tweaksOnly: Boolean,
        val tweaksFullBasic: Boolean,
        val tweaksFullWithDustFilter: Boolean,
        val tweaksCutThroughWithDustFilter: Boolean
    )

    /** Uma transação elegível do bloco — [tweak] já é `input_hash · A`
     *  pré-computado pelo servidor (ver documentação da classe). */
    data class TxTweakItem(
        val txidLE: ByteArray,
        val tweak: ByteArray,
        val outputsShort: List<ByteArray>
    )

    data class BlockScanData(
        val blockHashLE: ByteArray,
        val blockHeight: Long,
        val txs: List<TxTweakItem>,
        /** 8 bytes cada, pubkeys truncadas de TODO output gasto no bloco
         *  (não só os nossos) — usado só pra podar UTXOs SP nossos que já
         *  foram gastos, sem precisar de outro scan completo. */
        val spentOutputsShort: List<ByteArray>
    )

    private fun grpcClient(baseUrl: String): GrpcClient {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // O gzip transparente do OkHttp (Accept-Encoding automático) não
            // convive bem com corpo HTTP/2 duplex de streaming gRPC — o
            // servidor (ou um proxy na frente) responde comprimido e o Wire
            // não tem decompressor de grpc-encoding gzip instalado, falhando
            // com "Decompressor is not installed for grpc-encoding gzip"
            // (confirmado ao vivo contra o oracle público nesta sessão).
            // Declarar Accept-Encoding manualmente desliga o gzip automático
            // do OkHttp e sinaliza pro servidor não comprimir.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Accept-Encoding", "identity").build())
            }
            .build()
        return GrpcClient.Builder()
            .client(okHttp)
            .baseUrl(baseUrl)
            // Wire comprime a REQUISIÇÃO com gzip por padrão (minMessageToCompress
            // default é 0 — até corpo vazio bate o limiar) — o servidor Go do
            // blindbit-oracle não registra o codec gzip (comportamento padrão do
            // grpc-go: nenhum compressor habilitado sem import explícito), e
            // rejeita com "Decompressor is not installed for grpc-encoding gzip"
            // (confirmado ao vivo contra o oracle público nesta sessão). As
            // mensagens daqui são pequenas (uma faixa de altura, no máximo), sem
            // ganho real em comprimir — desliga.
            .minMessageToCompress(Long.MAX_VALUE)
            .build()
    }

    private fun service(baseUrl: String): OracleServiceClient = grpcClient(baseUrl).create()

    /** Chame ANTES de escanear: diz que dados o servidor tem disponíveis
     *  (tweaks_only vs full) e até que altura ele já indexou. */
    suspend fun getInfo(baseUrl: String): OracleInfo {
        val r = service(baseUrl).GetInfo().execute(Unit)
        return OracleInfo(
            network                       = r.network,
            height                        = r.height,
            tweaksOnly                    = r.tweaks_only,
            tweaksFullBasic               = r.tweaks_full_basic,
            tweaksFullWithDustFilter      = r.tweaks_full_with_dust_filter,
            tweaksCutThroughWithDustFilter = r.tweaks_cut_through_with_dust_filter
        )
    }

    private fun chunk8(flat: ByteString): List<ByteArray> {
        val bytes = flat.toByteArray()
        require(bytes.size % 8 == 0) { "array de pubkeys truncadas com tamanho não múltiplo de 8: ${bytes.size} bytes" }
        return (bytes.indices step 8).map { bytes.copyOfRange(it, it + 8) }
    }

    /**
     * Stream incremental de dados de scan, [startHeight]..[endHeight]
     * (inclusive dos dois lados) — um item por bloco, em ordem crescente
     * de altura. Cancelar a coleta do [Flow] cancela a chamada gRPC
     * subjacente (não deixa o stream do servidor pendurado).
     */
    fun streamBlockScanDataShort(baseUrl: String, startHeight: Long, endHeight: Long): Flow<BlockScanData> = channelFlow {
        require(startHeight >= 0 && endHeight >= startHeight) {
            "faixa de altura inválida: start=$startHeight end=$endHeight"
        }
        val call = service(baseUrl).StreamBlockScanDataShort()
        try {
            val (sendChannel, receiveChannel) = call.executeIn(this)
            sendChannel.send(RangedBlockHeightRequestFiltered(start = startHeight, end = endHeight))
            sendChannel.close()
            for (block in receiveChannel) {
                val id = block.block_identifier ?: continue
                send(
                    BlockScanData(
                        blockHashLE = id.block_hash.toByteArray(),
                        blockHeight = id.block_height,
                        txs = block.comp_index.map { item ->
                            TxTweakItem(
                                txidLE       = item.txid.toByteArray(),
                                tweak        = item.tweak.toByteArray(),
                                outputsShort = chunk8(item.outputs_short)
                            )
                        },
                        spentOutputsShort = chunk8(block.spent_outputs)
                    )
                )
            }
        } finally {
            call.cancel()
        }
    }
}
