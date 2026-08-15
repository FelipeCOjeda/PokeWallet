package com.pokewallet.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cobre a correção do achado da auditoria (item 3): sem
 * [NostrRelayClient.acceptedReplyContent] restringir por pubkey, qualquer
 * participante do canal público conseguiria forjar uma "confirmação" de
 * broadcast citando o txid (que é público, extraído do próprio
 * `!broadcast <hex>` publicado pela wallet).
 */
class NostrRelayClientTest {

    private val ourPubkey = "aa".repeat(32)
    private val botPubkey = "bb".repeat(32)
    private val attackerPubkey = "cc".repeat(32)

    private fun eventFrame(pubkey: String, content: String): String {
        val escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return """["EVENT","sub1",{"pubkey":"$pubkey","content":"$escapedContent","kind":20000}]"""
    }

    private val txidMatcher: (String) -> Boolean = { it.contains("deadbeef") }

    @Test
    fun `aceita resposta da pubkey esperada do bot`() {
        val frame = eventFrame(botPubkey, "broadcast ok deadbeef")
        val result = NostrRelayClient.acceptedReplyContent(frame, ourPubkey, botPubkey, txidMatcher)
        assertEquals("broadcast ok deadbeef", result)
    }

    @Test
    fun `rejeita resposta forjada por uma pubkey diferente da esperada - vetor do achado 3`() {
        val frameForjado = eventFrame(attackerPubkey, "broadcast ok deadbeef")
        val result = NostrRelayClient.acceptedReplyContent(frameForjado, ourPubkey, botPubkey, txidMatcher)
        assertNull(result)
    }

    @Test
    fun `sem pubkey esperada informada, aceita de qualquer publicador - comportamento antigo preservado`() {
        val frame = eventFrame(attackerPubkey, "broadcast ok deadbeef")
        val result = NostrRelayClient.acceptedReplyContent(frame, ourPubkey, null, txidMatcher)
        assertEquals("broadcast ok deadbeef", result)
    }

    @Test
    fun `ignora eco do nosso proprio evento mesmo se a pubkey esperada fosse a nossa`() {
        val frame = eventFrame(ourPubkey, "broadcast ok deadbeef")
        val result = NostrRelayClient.acceptedReplyContent(frame, ourPubkey, ourPubkey, txidMatcher)
        assertNull(result)
    }

    @Test
    fun `comparacao de pubkey esperada e case-insensitive`() {
        val frame = eventFrame(botPubkey.uppercase(), "broadcast ok deadbeef")
        val result = NostrRelayClient.acceptedReplyContent(frame, ourPubkey, botPubkey, txidMatcher)
        assertEquals("broadcast ok deadbeef", result)
    }

    @Test
    fun `rejeita quando o conteudo nao bate no matcher mesmo vindo da pubkey certa`() {
        val frame = eventFrame(botPubkey, "mensagem sem o txid esperado")
        val result = NostrRelayClient.acceptedReplyContent(frame, ourPubkey, botPubkey, txidMatcher)
        assertNull(result)
    }

    @Test
    fun `frames que nao sao EVENT sao ignorados`() {
        val okFrame = """["OK","eventid123",true,""]"""
        val result = NostrRelayClient.acceptedReplyContent(okFrame, ourPubkey, botPubkey, txidMatcher)
        assertNull(result)
    }
}
