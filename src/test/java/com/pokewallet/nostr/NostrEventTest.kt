package com.pokewallet.nostr

import com.pokewallet.crypto.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vetor de ouro gerado com o `nostr-tools` de verdade (o mesmo pacote
 * que o bitchat-broadcaster usa), via:
 *
 *   node -e "import('nostr-tools/pure').then(p => {
 *     const evt = { pubkey, created_at, kind, tags, content };
 *     console.log(p.serializeEvent(evt), p.getEventHash(evt));
 *   })"
 *
 * Content inclui propositalmente `/`, `"`, `\`, quebra de linha e tab
 * pra exercitar as regras de escaping do NIP-01.
 */
class NostrEventTest {

    private val pubkeyHex = "881e08087a6556d601ab5ec7beb8cd38f00c8510ffda758d7fa41b4387a852e9"
    private val privKeyHex = "6374f81764b5701a1d20ab3adf653a35b7495af7ba0ad41a2e8acfd1040b566f"
    private val createdAt = 1700000000L
    private val kind = 20000
    private val tags = listOf(listOf("g", "6g"))
    private val content = "!broadcast deadbeef0123456789 tags/slashes \"quotes\" \\backslash\\ linebreak\ntab\t"

    private val expectedSerialized =
        "[0,\"881e08087a6556d601ab5ec7beb8cd38f00c8510ffda758d7fa41b4387a852e9\",1700000000,20000,[[\"g\",\"6g\"]],\"!broadcast deadbeef0123456789 tags/slashes \\\"quotes\\\" \\\\backslash\\\\ linebreak\\ntab\\t\"]"

    private val expectedId = "773741448ad5b67da48fe591638d095dff0efcb6f0f6c6448395391151540445"

    @Test
    fun `serialize matches nostr-tools byte-for-byte`() {
        val serialized = NostrEvent.serialize(pubkeyHex, createdAt, kind, tags, content)
        assertEquals(expectedSerialized, serialized)
    }

    @Test
    fun `id matches nostr-tools getEventHash`() {
        val serialized = NostrEvent.serialize(pubkeyHex, createdAt, kind, tags, content)
        val idHex = NostrEvent.id(serialized).joinToString("") { "%02x".format(it) }
        assertEquals(expectedId, idHex)
    }

    @Test
    fun `build produces a self-consistent signed event`() {
        val privKey = privKeyHex.hexToBytes()
        val pubKey = pubkeyHex.hexToBytes()

        val event = NostrEvent.build(
            privKey32 = privKey,
            pubKey32 = pubKey,
            kind = kind,
            tags = tags,
            content = content,
            createdAt = createdAt
        )

        assertEquals(expectedId, event.id)
        assertEquals(pubkeyHex, event.pubkey)
        assertEquals(0, event.sig.length % 2) // sanity: hex par
        assertTrue("sig deve ter 64 bytes (128 hex chars)", event.sig.length == 128)
    }

    @Test
    fun `toRelayMessage produces valid EVENT wire frame`() {
        val event = NostrEvent.build(
            privKey32 = privKeyHex.hexToBytes(),
            pubKey32 = pubkeyHex.hexToBytes(),
            kind = kind,
            tags = tags,
            content = content,
            createdAt = createdAt
        )
        val msg = NostrEvent.toRelayMessage(event)
        assertTrue(msg.startsWith("[\"EVENT\","))
        assertTrue(msg.contains("\"id\":\"$expectedId\""))
        assertTrue(msg.endsWith("]"))
    }
}
