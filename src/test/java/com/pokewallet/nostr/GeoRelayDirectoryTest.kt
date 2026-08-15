package com.pokewallet.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre a correção do achado da auditoria (item 5): uma linha malformada
 * ou adulterada no CSV do diretório de relays (baixado de um repositório
 * de terceiro, `raw.githubusercontent.com/permissionlesstech/bitchat`)
 * não pode virar literalmente parte de uma URL `wss://` sem validação.
 */
class GeoRelayDirectoryTest {

    @Test
    fun `aceita hosts validos do formato real do bitchat`() {
        val csv = """
            host,lat,lon
            relay.damus.io,40.7128,-74.0060
            nos.lol,51.5074,-0.1278
            sub.dominio.example.com,-23.5,-46.6
        """.trimIndent()

        val entries = GeoRelayDirectory.parseDirectoryCsv(csv)

        assertEquals(3, entries.size)
        assertEquals(setOf("relay.damus.io", "nos.lol", "sub.dominio.example.com"), entries.map { it.host }.toSet())
    }

    @Test
    fun `rejeita linha com host contendo caminho - nao pode virar parte da URL wss`() {
        val csv = """
            host,lat,lon
            relay.damus.io,40.7128,-74.0060
            evil.com/malicious-path,10.0,10.0
        """.trimIndent()

        val entries = GeoRelayDirectory.parseDirectoryCsv(csv)

        assertEquals(1, entries.size)
        assertEquals("relay.damus.io", entries[0].host)
    }

    @Test
    fun `rejeita linha com host contendo espaco, arroba ou dois-pontos`() {
        val csv = """
            host,lat,lon
            relay bom.io,1.0,1.0
            usuario@relay.io,2.0,2.0
            relay.io:9999,3.0,3.0
        """.trimIndent()

        val entries = GeoRelayDirectory.parseDirectoryCsv(csv)

        assertTrue("nenhuma linha malformada deveria passar", entries.isEmpty())
    }

    @Test
    fun `rejeita host sem nenhum ponto - nao e um dominio plausivel`() {
        val csv = """
            host,lat,lon
            semdominio,1.0,1.0
        """.trimIndent()

        val entries = GeoRelayDirectory.parseDirectoryCsv(csv)

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `ignora linha com lat-lon nao numerico sem quebrar as demais`() {
        val csv = """
            host,lat,lon
            relay.damus.io,40.7128,-74.0060
            relay.quebrado.io,not-a-number,-46.6
        """.trimIndent()

        val entries = GeoRelayDirectory.parseDirectoryCsv(csv)

        assertEquals(1, entries.size)
        assertEquals("relay.damus.io", entries[0].host)
    }
}
