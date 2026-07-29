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
                                    val evtJson = arr.optJSONObject(2) ?: return
                                    if (evtJson.optString("pubkey") == ourPubkeyHex) return
                                    val content = evtJson.optString("content")
                                    if (matcher(content) && !deferred.isCompleted) {
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
}
