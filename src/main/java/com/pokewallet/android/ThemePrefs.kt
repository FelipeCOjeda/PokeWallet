package com.pokewallet.android

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.pokewallet.R

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

    /**
     * Liga o par de botões CLARO/ESCURO ao estado atual — destaca visualmente
     * qual está ativo e troca (com recreate da Activity) ao tocar no outro.
     * Usado tanto na tela de Setup (antes de existir wallet) quanto na Mochila.
     */
    fun bindToggle(activity: FragmentActivity, btnLight: MaterialButton, btnDark: MaterialButton) {
        fun refresh() {
            val dark = isDarkMode(activity)
            btnLight.setBackgroundResource(
                if (!dark) R.drawable.bg_gameboy_button_solid else R.drawable.bg_gameboy_button_outline)
            btnLight.backgroundTintList =
                if (!dark) ContextCompat.getColorStateList(activity, R.color.accent_yellow) else null
            btnDark.setBackgroundResource(
                if (dark) R.drawable.bg_gameboy_button_solid else R.drawable.bg_gameboy_button_outline)
            btnDark.backgroundTintList =
                if (dark) ContextCompat.getColorStateList(activity, R.color.accent_yellow) else null
        }
        refresh()

        btnLight.setOnClickListener {
            if (isDarkMode(activity)) {
                setDarkMode(activity, false)
                activity.recreate()
            }
        }
        btnDark.setOnClickListener {
            if (!isDarkMode(activity)) {
                setDarkMode(activity, true)
                activity.recreate()
            }
        }
    }
}
