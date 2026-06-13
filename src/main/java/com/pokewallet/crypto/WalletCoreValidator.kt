package com.pokewallet.crypto

object WalletCoreValidator {

    /**
     * Garante que a wallet existe e está carregada no Bitcoin Core.
     *
     * Contrato:
     * - walletName DEVE existir no Core
     * - walletName DEVE estar carregada
     *
     * Falha aqui é erro estrutural, não de fluxo.
     */
    fun assertWalletExists(walletName: String) {

        val rpc = BitcoinRpcClient(wallet = walletName)

        try {
            // chamada canônica: só wallets válidas respondem
            rpc.call("getwalletinfo")
        } catch (e: Exception) {

            val rootMessage = e.message ?: "erro desconhecido"

            error(
                """
                ❌ Wallet '$walletName' não está disponível no Bitcoin Core.

                Detalhes técnicos:
                $rootMessage

                Possíveis causas:
                • A wallet não foi criada no Core
                • A wallet existe, mas não está carregada
                • O nome em wallet.json não corresponde ao nome no Core
                • O Bitcoin Core não está rodando ou não é o mesmo datadir

                Comandos úteis para diagnóstico:
                bitcoin-cli -regtest listwallets
                bitcoin-cli -regtest loadwallet "$walletName"
                bitcoin-cli -regtest listwalletdir

                Observação:
                O nome da wallet no wallet.json é um CONTRATO.
                Ele deve bater exatamente com o nome no Bitcoin Core.
                """.trimIndent()
            )
        }
    }
}
