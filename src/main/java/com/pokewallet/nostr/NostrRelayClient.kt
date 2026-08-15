package com.pokewallet.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/** Resultado de uma publicação: se pelo menos um relay confirmou recebimento (`OK`), e o conteúdo da resposta que bateu no matcher (se alguma chegou a tempo). */
data class PublishResult(val published: Boolean, val replyContent: String?)

/**
 * Cliente Nostr mínimo: publica um evento em vários relays em paralelo
 * e opcionalmente espera uma resposta que bata num critério (matcher),
 * com timeout. Não é um cliente Nostr genérico — só o suficiente pra
 * mandar `!broadcast <hex>` pro bitchat-broadcaster e, se der tempo,
 * capturar a confirmação dele.
 */
object NostrRelayClient {

    suspend fun publishAndAwaitReply(
        event: SignedEvent,
        relays: List<String>,
        ourPubkeyHex: String,
        geohash: String,
        timeoutMs: Long = 18_000L,
        /**
         * Pubkey esperada de quem confirma o broadcast (ex.: a pubkey fixa
         * do bitchat-broadcaster) — quando informada, uma resposta `EVENT`
         * assinada por qualquer OUTRA pubkey é ignorada. Sem isso, qualquer
         * participante do canal público (que é público por definição) pode
         * forjar uma confirmação contendo o txid — que é público, extraído
         * do próprio conteúdo `!broadcast <hex>` que a wallet acabou de
         * publicar — e a wallet acreditaria que a tx foi transmitida sem
         * ela ter sido de fato. `null` mantém o comportamento antigo
         * (aceita de qualquer pubkey) — usado só se o chamador não souber a
         * identidade do bot esperado.
         */
        expectedReplyPubkeyHex: String? = null,
        matcher: (content: String) -> Boolean
    ): PublishResult {
        val client = OkHttpClient()
        val deferred = CompletableDeferred<String>()
        val publishedOk = AtomicBoolean(false)
        val sockets = mutableListOf<WebSocket>()

        val eventMsg = NostrEvent.toRelayMessage(event)
        val subId = "wallet${System.currentTimeMillis()}"
        val reqMsg = "[\"REQ\",\"$subId\",{\"kinds\":[${event.kind}],\"#g\":[\"${escapeForFilter(geohash)}\"]}]"

        try {
            relays.forEach { url ->
                val request = Request.Builder().url(url).build()
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(eventMsg)
                        webSocket.send(reqMsg)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleFrame(text)
                    }

                    private fun handleFrame(text: String) {
                        try {
                            val arr = JSONArray(text)
                            when (arr.optString(0)) {
                                "OK" -> {
                                    if (arr.optString(1) == event.id && arr.optBoolean(2, false)) {
                                        publishedOk.set(true)
                                    }
                                }
                                "EVENT" -> {
                                    val content = acceptedReplyContent(text, ourPubkeyHex, expectedReplyPubkeyHex, matcher)
                                    if (content != null && !deferred.isCompleted) {
                                        deferred.complete(content)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // frame não reconhecido/malformado — ignora, não derruba o cliente
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        // relay individual falhou (timeout, DNS, 5xx) — os outros ainda podem funcionar
                    }
                })
                sockets.add(ws)
            }

            val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
            return PublishResult(published = publishedOk.get(), replyContent = reply)
        } finally {
            sockets.forEach { it.close(1000, null) }
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun escapeForFilter(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Decide se um frame `["EVENT", subId, {...}]` recebido de um relay
     * conta como resposta válida: ignora o eco do nosso próprio evento e,
     * se [expectedReplyPubkeyHex] for informado, só aceita conteúdo
     * assinado por essa pubkey exata (ver doc de [publishAndAwaitReply]).
     * Extraída como função pura (sem WebSocket) pra ser testável
     * isoladamente. Retorna o `content` aceito, ou `null` se o frame não é
     * um EVENT válido, é nosso próprio eco, é de uma pubkey inesperada, ou
     * não bate no [matcher].
     */
    internal fun acceptedReplyContent(
        frameText: String,
        ourPubkeyHex: String,
        expectedReplyPubkeyHex: String?,
        matcher: (content: String) -> Boolean
    ): String? {
        val arr = JSONArray(frameText)
        if (arr.optString(0) != "EVENT") return null
        val evtJson = arr.optJSONObject(2) ?: return null
        val pubkey = evtJson.optString("pubkey")
        if (pubkey == ourPubkeyHex) return null
        if (expectedReplyPubkeyHex != null && !pubkey.equals(expectedReplyPubkeyHex, ignoreCase = true)) return null
        val content = evtJson.optString("content")
        return if (matcher(content)) content else null
    }
}
