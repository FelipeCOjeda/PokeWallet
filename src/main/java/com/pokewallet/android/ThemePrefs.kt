package com.pokewallet.android

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Preferência manual de tema claro/escuro (opção na Mochila) — não segue
 * o tema do sistema, é uma escolha explícita do usuário, persistida em
 * SharedPreferences e aplicada via AppCompatDelegate.setDefaultNightMode.
 */
object ThemePrefs {

    private const val PREFS_NAME = "pokewallet_prefs"
    private const val KEY_DARK   = "dark_mode"

    fun isDarkMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, false)

    /** Chama no Application.onCreate(), antes de qualquer Activity — evita flash do tema errado. */
    fun applyStartup(context: Context) {
        val mode = if (isDarkMode(context)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Troca o tema, persiste a escolha e aplica. A Activity recria sozinha (AppCompat). */
    fun setDarkMode(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK, dark)
            .apply()
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
