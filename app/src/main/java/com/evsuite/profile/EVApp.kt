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
        // Catch any unhandled exception, write stack trace + logs
        // AppLogger in filesDir/last_crash.txt, then leaves the default handler
        // complete the process normally (Android displays its own crash screen).
        CrashLogger.install(this, "EVProfile")

        // ── Migration + theme initialization ───────────────────────────────
        val prefs = getSharedPreferences("ev_settings", Context.MODE_PRIVATE)

        if (!prefs.contains(ThemeHelper.PREF_THEME_MODE)) {
            // Migration from the old boolean "dark_theme" (version < 2.x)
            // On new installation: "auto" (all firmwares support it)
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
