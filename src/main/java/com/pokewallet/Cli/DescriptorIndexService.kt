package com.pokewallet.crypto

import org.json.JSONObject

/**
 * DescriptorIndexService
 *
 * Fonte da verdade do índice de derivação.
 *
 * Lê diretamente do Bitcoin Core via listdescriptors.
 * wallet.json é apenas cache / espelho.
 */
object DescriptorIndexService {

    fun nextExternalIndex(walletName: String): Int {

        val rpc = BitcoinRpcClient(wallet = walletName)

        val result = rpc.call("listdescriptors") as JSONObject
        val descriptors = result.getJSONArray("descriptors")

        for (i in 0 until descriptors.length()) {

            val desc = descriptors.getJSONObject(i)

            val isActive = desc.optBoolean("active", false)
            val isInternal = desc.optBoolean("internal", true)

            if (isActive && !isInternal) {

                if (!desc.has("next_index")) {
                    error("Descriptor ativo sem next_index (estado inválido)")
                }

                return desc.getInt("next_index")
            }
        }

        error("Nenhum descriptor externo ativo encontrado no wallet $walletName")
    }
}
