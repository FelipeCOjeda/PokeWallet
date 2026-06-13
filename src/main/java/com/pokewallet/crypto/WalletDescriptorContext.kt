package com.pokewallet.crypto

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Contexto CANÔNICO derivado de wallet.json.
 *
 * Fonte da verdade:
 * - wallet.json
 *
 * Responsabilidades:
 * - Expor descriptors
 * - Garantir wallet carregada
 * - Garantir descriptors importados
 *
 * NÃO FAZ:
 * - Heurística
 * - Scan manual
 * - Derivação
 */
class WalletDescriptorContext private constructor(
    val walletName: String,
    val fingerprint: String,
    val externalDescriptor: String,
    val internalDescriptor: String,
    val rpc: BitcoinRpcClient
) {

    companion object {

        fun fromWalletJson(
            walletJsonPath: Path = Path.of("wallet.json"),
            rpcHost: String = "127.0.0.1",
            rpcPort: Int = 18443
        ): WalletDescriptorContext {

            require(Files.exists(walletJsonPath)) {
                "wallet.json não encontrado em: $walletJsonPath"
            }

            val json = JSONObject(walletJsonPath.toFile().readText())

            val walletName = json.getString("walletName")
            val fingerprint = json.getString("fingerprint")
            val externalDescriptor = json.getString("externalDescriptor")
            val internalDescriptor = json.getString("internalDescriptor")

            // Sanidade mínima
            require(externalDescriptor.contains("[$fingerprint")) {
                "Fingerprint não confere com externalDescriptor"
            }
            require(internalDescriptor.contains("[$fingerprint")) {
                "Fingerprint não confere com internalDescriptor"
            }

            val rpc = BitcoinRpcClient(
                host = rpcHost,
                port = rpcPort,
                wallet = walletName
            )

            return WalletDescriptorContext(
                walletName = walletName,
                fingerprint = fingerprint,
                externalDescriptor = externalDescriptor,
                internalDescriptor = internalDescriptor,
                rpc = rpc
            )
        }
    }

    /**
     * Garante que a wallet está carregada no Core.
     */
    fun ensureWalletLoaded() {
        try {
            rpc.getWalletInfo()
        } catch (_: Exception) {
            rpc.call("loadwallet", listOf(walletName))
        }
    }

    /**
     * Importa descriptors de forma idempotente.
     *
     * - Se já existem → Core ignora
     * - Se não existem → importa
     */
    fun ensureDescriptorsImported() {

        val requests = listOf(
            JSONObject()
                .put("desc", externalDescriptor)
                .put("active", true)
                .put("internal", false)
                .put("timestamp", "now"),

            JSONObject()
                .put("desc", internalDescriptor)
                .put("active", true)
                .put("internal", true)
                .put("timestamp", "now")
        )

        rpc.importDescriptors(requests)
    }
}
