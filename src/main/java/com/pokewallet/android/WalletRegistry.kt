package com.pokewallet.android

import android.content.Context
import java.io.File

/**
 * Registro de múltiplas carteiras: qual está ativa + nome de exibição de
 * cada uma (SharedPreferences, mesmo padrão de ThemePrefs.kt). A LISTA de
 * carteiras NUNCA é persistida aqui — é sempre derivada ao vivo escaneando
 * o disco (wallets/<fingerprint>/wallet.json), pra nunca existir uma
 * segunda fonte de verdade que possa dessincronizar do que realmente
 * existe em disco.
 */
object WalletRegistry {

    private const val PREFS_NAME  = "pokewallet_wallets"
    private const val KEY_ACTIVE  = "active_wallet_id"
    private const val LABEL_PREFIX = "label_"

    const val WALLETS_DIR_NAME = "wallets"
    const val PENDING_DIR_NAME = "_pending"

    fun walletsRoot(filesDir: File): File = File(filesDir, WALLETS_DIR_NAME)

    fun walletDir(filesDir: File, walletId: String): File = File(walletsRoot(filesDir), walletId)

    fun pendingDir(filesDir: File): File = File(walletsRoot(filesDir), PENDING_DIR_NAME)

    /**
     * Fingerprints de todas as carteiras conhecidas (têm wallet.json de
     * verdade dentro), lidos direto do disco — ignora o diretório
     * transitório _pending.
     */
    fun listKnownWalletIds(filesDir: File): List<String> =
        walletsRoot(filesDir).listFiles()
            ?.filter { it.isDirectory && it.name != PENDING_DIR_NAME && File(it, "wallet.json").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun activeWalletId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, null)

    fun setActiveWalletId(context: Context, walletId: String) {
        prefs(context).edit().putString(KEY_ACTIVE, walletId).apply()
    }

    fun clearActiveWalletId(context: Context) {
        prefs(context).edit().remove(KEY_ACTIVE).apply()
    }

    fun getDisplayName(context: Context, walletId: String): String? =
        prefs(context).getString(LABEL_PREFIX + walletId, null)

    fun setDisplayName(context: Context, walletId: String, name: String) {
        prefs(context).edit().putString(LABEL_PREFIX + walletId, name).apply()
    }

    /** Remove a entrada do registro (nome + ativa se for o caso) — usado ao esquecer uma carteira. */
    fun removeWallet(context: Context, walletId: String) {
        val editor = prefs(context).edit().remove(LABEL_PREFIX + walletId)
        if (activeWalletId(context) == walletId) editor.remove(KEY_ACTIVE)
        editor.apply()
    }

    /** Nome de exibição com fallback determinístico (nunca null) — "Carteira XXXX" a partir do fingerprint. */
    fun displayNameOrFallback(context: Context, walletId: String): String =
        getDisplayName(context, walletId) ?: "Carteira ${walletId.take(4).uppercase()}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
