package com.pokewallet.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.util.HexFormat

/**
 * Indexador determinístico de UTXOs baseado em descriptors.
 *
 * Fonte da verdade:
 * - Bitcoin Core (listunspent)
 * - Descriptor canônico (parent_descs)
 *
 * NÃO depende de:
 * - seed
 * - fingerprint
 * - network
 * - estado local
 */
object WalletUtxoIndex {

    data class IndexedUtxo(
        val txid: ByteArray,
        val vout: Int,
        val valueSats: Long,
        val branch: Int, // 0 = external, 1 = internal
        val index: Int,
        val desc: String
    )

    data class Result(
        val utxos: List<IndexedUtxo>,
        val totalSats: Long
    )

    fun index(
        rpc: BitcoinRpcClient,
        externalDescriptor: String,
        internalDescriptor: String
    ): Result {

        val utxosJson = rpc.call(
            "listunspent",
            listOf(
                0,
                9999999,
                emptyList<String>(),
                true
            )
        ) as JSONArray

        val externalCanonical = normalizeDescriptor(externalDescriptor)
        val internalCanonical = normalizeDescriptor(internalDescriptor)

        val utxos = mutableListOf<IndexedUtxo>()
        var total = 0L

        val hex = HexFormat.of()

        for (i in 0 until utxosJson.length()) {

            val u = utxosJson.getJSONObject(i)

            val branch = resolveBranch(
                utxo = u,
                externalCanonical = externalCanonical,
                internalCanonical = internalCanonical
            ) ?: continue

            val resolvedDesc = u.getString("desc")

            val index = extractIndexFromResolvedDescriptor(resolvedDesc)
                ?: continue

            val valueSats =
                (u.getDouble("amount") * 100_000_000).toLong()

            total += valueSats

            val txidBytes =
                hex.parseHex(u.getString("txid"))

            utxos += IndexedUtxo(
                txid = txidBytes,
                vout = u.getInt("vout"),
                valueSats = valueSats,
                branch = branch,
                index = index,
                desc = resolvedDesc
            )
        }

        return Result(
            utxos = utxos.sortedWith(
                compareBy({ it.branch }, { it.index })
            ),
            totalSats = total
        )
    }

    // ======================================================
    // Helpers internos (determinísticos)
    // ======================================================

    private fun normalizeDescriptor(desc: String): String {
        return desc.substringBefore("#")
    }

    private fun resolveBranch(
        utxo: JSONObject,
        externalCanonical: String,
        internalCanonical: String
    ): Int? {

        // 1️⃣ Preferência: parent_descs (canônico)
        if (utxo.has("parent_descs")) {
            val parents = utxo.getJSONArray("parent_descs")
            for (j in 0 until parents.length()) {
                val parent =
                    normalizeDescriptor(parents.getString(j))

                when (parent) {
                    externalCanonical -> return 0
                    internalCanonical -> return 1
                }
            }
        }

        // 2️⃣ Fallback seguro: comparar prefixo estrutural
        val resolved = utxo.getString("desc")

        return when {
            resolved.contains("/0/") -> 0
            resolved.contains("/1/") -> 1
            else -> null
        }
    }

    private fun extractIndexFromResolvedDescriptor(desc: String): Int? {
        return desc
            .substringBefore(")")
            .substringAfterLast("/")
            .toIntOrNull()
    }
}
