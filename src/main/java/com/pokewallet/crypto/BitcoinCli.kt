package com.pokewallet.crypto

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * BitcoinCli
 *
 * Camada mínima de integração com o Bitcoin Core via bitcoin-cli.
 * Responsável apenas por executar comandos e devolver texto bruto.
 *
 * NÃO faz parsing
 * NÃO conhece JSON
 * NÃO conhece lógica de wallet
 */
object BitcoinCli {

    private const val NETWORK = "-regtest"
    private const val WALLET = "pokewallet"

    /**
     * Executa `bitcoin-cli listunspent` para a wallet configurada
     * e retorna o output bruto (JSON).
     *
     * Falha explicitamente se o retorno não for um JSON array.
     */
    fun listUnspent(): String {

        val process = ProcessBuilder(
            "bitcoin-cli",
            NETWORK,
            "-rpcwallet=$WALLET",
            "listunspent"
        )
            .redirectErrorStream(true)
            .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()

        process.waitFor()

        require(output.isNotBlank()) {
            "bitcoin-cli listunspent retornou vazio"
        }

        require(output.trim().startsWith("[")) {
            "bitcoin-cli listunspent não retornou JSON array:\n$output"
        }

        return output
    }

    /**
     * Executa `bitcoin-cli importdescriptors` com o JSON fornecido.
     *
     * Recebe o payload pronto (string),
     * não constrói descriptors,
     * não valida conteúdo semântico.
     */
    fun importDescriptors(descriptorsJson: String) {

        val process = ProcessBuilder(
            "bitcoin-cli",
            NETWORK,
            "-rpcwallet=$WALLET",
            "importdescriptors",
            descriptorsJson
        )
            .redirectErrorStream(true)
            .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()

        process.waitFor()

        require(process.exitValue() == 0) {
            "Erro ao executar importdescriptors:\n$output"
        }
    }
}

