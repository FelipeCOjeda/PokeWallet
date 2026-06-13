package com.pokewallet.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Wrapper canônico e wallet-scoped para bitcoin-cli.
 *
 * REGRAS IMUTÁVEIS:
 * - Toda chamada é feita com -rpcwallet
 * - Não existe "wallet padrão"
 * - Erros do Core viram exceção explícita
 */
class BitcoinCli(private val walletName: String) {

    init {
        require(walletName.isNotBlank()) {
            "walletName não pode ser vazio"
        }
    }

    // -------------------------------------------------
    // Execução de comando
    // -------------------------------------------------
    private fun runCli(args: List<String>): String {

        val cmd = mutableListOf(
            "bitcoin-cli",
            "-regtest",
            "-rpcwallet=$walletName"
        ).apply {
            addAll(args)
        }

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()

        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lines().forEach { output.appendLine(it) }
        }

        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalArgumentException(
                "bitcoin-cli falhou (exit=$exitCode):\n${output}"
            )
        }

        return output.toString().trim()
    }

    // -------------------------------------------------
    // RPC genérico
    // -------------------------------------------------
    fun call(method: String, params: List<Any> = emptyList()): Any {

        val jsonParams = params.joinToString(
            prefix = "[",
            postfix = "]"
        ) { p ->
            when (p) {
                is String -> "\"$p\""
                else -> p.toString()
            }
        }

        val raw = runCli(
            listOf(method) +
                if (params.isNotEmpty()) listOf(jsonParams) else emptyList()
        )

        return Json.parse(raw)
    }

    // -------------------------------------------------
    // Helpers específicos
    // -------------------------------------------------
    fun listUnspent(
        minConf: Int = 0,
        maxConf: Int = 9999999,
        includeUnsafe: Boolean = true
    ): JSONArray {

        val result = call(
            "listunspent",
            listOf(
                minConf,
                maxConf,
                emptyList<String>(),
                includeUnsafe
            )
        )

        return result as JSONArray
    }

    fun getBalance(): Double =
        call("getbalance") as Double

    fun getNewAddress(): String =
        call("getnewaddress") as String

    fun sendToAddress(
        address: String,
        amountBtc: Double
    ): String =
        call(
            "sendtoaddress",
            listOf(address, amountBtc)
        ) as String
}

/**
 * JSON helper minimalista.
 * Evita dependência pesada de mapper.
 */
private object Json {

    fun parse(raw: String): Any =
        when {
            raw.startsWith("{") -> JSONObject(raw)
            raw.startsWith("[") -> JSONArray(raw)
            raw == "true"       -> true
            raw == "false"      -> false
            raw.matches(Regex("-?\\d+(\\.\\d+)?")) ->
                if (raw.contains(".")) raw.toDouble() else raw.toLong()
            else -> raw.trim('"')
        }
}
