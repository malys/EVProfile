package com.evsuite.profile

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.evsuite.hardware.diag.CrashLogger
import com.evsuite.profile.util.LocaleHelper
import com.evsuite.profile.util.ThemeHelper

class EVApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()

        // ── Crash handler global ──────────────────────────────────────────────
        // Intercepte toute exception non gérée, écrit la stack trace + les logs
        // AppLogger dans filesDir/last_crash.txt, puis laisse le handler par défaut
        // terminer le processus normalement (Android affiche son propre écran de crash).
        CrashLogger.install(this, "EVProfile")

        // ── Migration + initialisation du thème ───────────────────────────────
        val prefs = getSharedPreferences("ev_settings", Context.MODE_PRIVATE)

        if (!prefs.contains(ThemeHelper.PREF_THEME_MODE)) {
            // Migration depuis l'ancien booléen "dark_theme" (version < 2.x)
            // Sur nouvelle installation : "auto" (tous les firmwares le supportent)
            val defaultMode = when {
                prefs.contains("dark_theme") ->
                    if (prefs.getBoolean("dark_theme", true)) "dark" else "light"
                else -> "auto"
            }
            prefs.edit().putString(ThemeHelper.PREF_THEME_MODE, defaultMode).apply()
        }

        AppCompatDelegate.setDefaultNightMode(ThemeHelper.resolveNightMode(this))
    }
}
