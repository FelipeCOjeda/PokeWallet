package com.pokewallet.nostr

import com.pokewallet.crypto.CryptoUtils
import com.pokewallet.crypto.SchnorrSigner
import com.pokewallet.crypto.toHex

/**
 * Evento Nostr assinado (NIP-01), pronto pra publicar num relay.
 */
data class SignedEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
)

/**
 * Construção e serialização de eventos Nostr (NIP-01).
 *
 * A serialização canônica usada pro cálculo do id precisa bater
 * EXATAMENTE com `JSON.stringify` do JavaScript (é o que o nostr-tools,
 * usado pelo bot bitchat-broadcaster, usa pra calcular o id do lado
 * dele) — em especial, `JSON.stringify` NUNCA escapa `/`, ao contrário
 * do escapador padrão de `org.json`. Por isso a serialização aqui é
 * feita à mão, não reusando nenhuma lib de JSON genérica.
 */
object NostrEvent {

    /**
     * Serialização canônica NIP-01: `[0, pubkey, created_at, kind, tags, content]`,
     * compacta (sem espaços), usada como entrada do sha256 que vira o id do evento.
     */
    fun serialize(
        pubkeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        return "[0,\"${escapeJsonString(pubkeyHex)}\",$createdAt,$kind,${tagsToJson(tags)},\"${escapeJsonString(content)}\"]"
    }

    /** sha256 da serialização canônica — id do evento (32 bytes). */
    fun id(serialized: String): ByteArray =
        CryptoUtils.sha256(serialized.toByteArray(Charsets.UTF_8))

    /**
     * Monta e assina um evento Nostr.
     *
     * @param privKey32 chave privada Nostr (32 bytes, ex: NostrKeys.deriveFromSeed)
     * @param pubKey32 chave pública x-only correspondente (32 bytes)
     */
    fun build(
        privKey32: ByteArray,
        pubKey32: ByteArray,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): SignedEvent {
        val pubkeyHex = pubKey32.toHex()
        val serialized = serialize(pubkeyHex, createdAt, kind, tags, content)
        val idBytes = id(serialized)
        val sigBytes = SchnorrSigner.sign(idBytes, privKey32)

        return SignedEvent(
            id = idBytes.toHex(),
            pubkey = pubkeyHex,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = sigBytes.toHex()
        )
    }

    /** Mensagem `["EVENT", {...}]` pronta pra mandar pro relay via WebSocket. */
    fun toRelayMessage(event: SignedEvent): String {
        val eventJson = "{" +
            "\"id\":\"${event.id}\"," +
            "\"pubkey\":\"${event.pubkey}\"," +
            "\"created_at\":${event.createdAt}," +
            "\"kind\":${event.kind}," +
            "\"tags\":${tagsToJson(event.tags)}," +
            "\"content\":\"${escapeJsonString(event.content)}\"," +
            "\"sig\":\"${event.sig}\"" +
            "}"
        return "[\"EVENT\",$eventJson]"
    }

    private fun tagsToJson(tags: List<List<String>>): String =
        tags.joinToString(",", prefix = "[", postfix = "]") { tag ->
            tag.joinToString(",", prefix = "[", postfix = "]") { "\"${escapeJsonString(it)}\"" }
        }

    /**
     * Escapa uma string pro formato que `JSON.stringify` produziria:
     * escapa `"`, `\`, e os control chars de forma curta (`\n \r \t \b \f`)
     * ou `\u00XX` pros demais < 0x20. Nunca escapa `/`.
     */
    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
