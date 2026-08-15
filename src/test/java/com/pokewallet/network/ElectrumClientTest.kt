package com.pokewallet.network

import com.pokewallet.crypto.AddressCodec
import com.pokewallet.crypto.CryptoUtils
import com.pokewallet.crypto.Network
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * `florestad`/qualquer servidor Electrum real não está disponível neste
 * ambiente de teste — os testes abaixo cobrem (1) o cálculo do scripthash
 * contra a fórmula OFICIAL do protocolo Electrum (SHA256(scriptPubKey)
 * revertido) recalculada de forma independente, e (2) o framing/parsing
 * JSON-RPC contra um servidor TCP FAKE em loopback (só ecoa o que o
 * protocolo Electrum de verdade devolveria), sem precisar de rede real.
 */
class ElectrumClientTest {

    private val mainnetAddress = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq" // endereço bech32 mainnet válido de exemplo

    @Test
    fun `scripthashOf bate com SHA256(scriptPubKey) revertido calculado independentemente`() {
        val client = ElectrumClient("127.0.0.1", 0)
        val spk    = AddressCodec.addressToScriptPubKey(mainnetAddress, Network.MAINNET)
        val expected = CryptoUtils.sha256(spk).reversedArray().joinToString("") { "%02x".format(it) }

        assertEquals(expected, client.scripthashOf(mainnetAddress, Network.MAINNET))
    }

    @Test
    fun `scripthashOf e deterministico e sempre 64 chars hex`() {
        val client = ElectrumClient("127.0.0.1", 0)
        val a = client.scripthashOf(mainnetAddress, Network.MAINNET)
        val b = client.scripthashOf(mainnetAddress, Network.MAINNET)
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    // ── Protocolo via servidor fake em loopback ──────────────

    @Test
    fun `getAddressStats via conexao persistente responde get_balance e get_history em sequencia`() {
        val server = ServerSocket(0)
        val port = server.localPort
        Thread {
            try {
                val socket = server.accept()
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                repeat(2) {
                    val line = reader.readLine() ?: return@repeat
                    val request = JSONObject(line)
                    val response = when (request.getString("method")) {
                        "blockchain.scripthash.get_balance" ->
                            """{"id":${request.getInt("id")},"result":{"confirmed":50000,"unconfirmed":0}}"""
                        "blockchain.scripthash.get_history" ->
                            """{"id":${request.getInt("id")},"result":[{"tx_hash":"aa","height":800000},{"tx_hash":"bb","height":800001},{"tx_hash":"cc","height":0}]}"""
                        else -> """{"id":${request.getInt("id")},"error":"inesperado"}"""
                    }
                    writer.write(response)
                    writer.write("\n")
                    writer.flush()
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        try {
            val client = ElectrumClient("127.0.0.1", port)
            val stats = client.getAddressStats(mainnetAddress, Network.MAINNET)
            assertEquals(50000L, stats.confirmedSats)
            assertEquals(0L, stats.pendingSats)
            assertEquals(2, stats.txCount)        // 2 com height > 0
            assertEquals(1, stats.mempoolTxCount)  // 1 com height <= 0
            assertTrue(stats.hasActivity)
            client.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `broadcast retorna o txid do result`() {
        val server = ServerSocket(0)
        val port = server.localPort
        Thread {
            try {
                val socket = server.accept()
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                val line = reader.readLine()
                val request = JSONObject(line)
                assertEquals("blockchain.transaction.broadcast", request.getString("method"))
                writer.write("""{"id":${request.getInt("id")},"result":"deadbeef"}""")
                writer.write("\n")
                writer.flush()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        try {
            val client = ElectrumClient("127.0.0.1", port)
            val txid = client.broadcast("0100000000", Network.MAINNET)
            assertEquals("deadbeef", txid)
            client.close()
        } finally {
            server.close()
        }
    }

    @Test(expected = RuntimeException::class)
    fun `erro do servidor Electrum vira excecao`() {
        val server = ServerSocket(0)
        val port = server.localPort
        Thread {
            try {
                val socket = server.accept()
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                val line = reader.readLine()
                val request = JSONObject(line)
                writer.write("""{"id":${request.getInt("id")},"error":{"code":-1,"message":"scripthash inválido"}}""")
                writer.write("\n")
                writer.flush()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        try {
            val client = ElectrumClient("127.0.0.1", port)
            client.broadcast("qualquer", Network.MAINNET)
        } finally {
            server.close()
        }
    }

    @Test
    fun `getRawTx devolve o hex bruto do result (verbose=false)`() {
        val server = ServerSocket(0)
        val port = server.localPort
        Thread {
            try {
                val socket = server.accept()
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                val line = reader.readLine()
                val request = JSONObject(line)
                assertEquals("blockchain.transaction.get", request.getString("method"))
                assertEquals("aabbcc", request.getJSONArray("params").getString(0))
                writer.write("""{"id":${request.getInt("id")},"result":"0200000001deadbeef"}""")
                writer.write("\n")
                writer.flush()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        try {
            val client = ElectrumClient("127.0.0.1", port)
            val rawHex = client.getRawTx("aabbcc", Network.MAINNET)
            assertEquals("0200000001deadbeef", rawHex)
            client.close()
        } finally {
            server.close()
        }
    }

    @Test(expected = RuntimeException::class)
    fun `conexao recusada (node fora do ar) vira excecao clara`() {
        // Porta livre garantida: sobe e fecha um ServerSocket na hora, sem
        // ninguém escutando nela em seguida.
        val probe = ServerSocket(0)
        val freePort = probe.localPort
        probe.close()

        val client = ElectrumClient("127.0.0.1", freePort, connectTimeoutMs = 1000)
        client.getFeeEstimates(Network.MAINNET)
    }
}
