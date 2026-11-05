package br.dev.bizo.cameramonitor

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Modo do tema: automático (segue o sistema), claro ou escuro. Escolhido em Ajustes. */
enum class ThemeMode(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
}

object AppTheme {
    private const val PREFS = "appearance"
    private const val KEY_MODE = "theme_mode"

    fun mode(context: Context): ThemeMode {
        val saved = prefs(context).getString(KEY_MODE, null)
        return ThemeMode.values().firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM
    }

    /** Salva e aplica na hora (as telas abertas são recriadas com o novo tema). */
    fun setMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    /** Aplica o modo salvo; chamado ao abrir o app. */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(mode(context).nightMode)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
