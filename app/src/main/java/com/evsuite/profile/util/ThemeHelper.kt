package com.evsuite.profile.util

import android.content.Context
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate

/**
 * Management of the application theme (dark / light / auto-sync launcher).
 *
 * "Auto" mode is available on ALL firmwares, but works differently:
 *
 * ─ SWI69 / SWI131 / SWI132 ──────────────────────────────────────────────────────
 *   Settings.System key: "SKIN_THEME_CONFIG" (0 = dark, 1 = light)
 *   Launcher broadcast: "com.saicmotor.changeSkin" (no extra → reread Settings.System)
 *   → We read the value explicitly and pass MODE_NIGHT_YES/NO + recreate().
 *
 * ─ SWI133 / SWI68 ───────────────────────────────────────────────────────────────
 *   The launcher calls UiModeManager.setNightMode() which changes the Android uiMode
 *   global (0x13 = clair, 0x23 = sombre). Le SkinManager SWI133 lit isNightMode()
 *   from Configuration.uiMode.
 *   → Use MODE_NIGHT_FOLLOW_SYSTEM so AppCompat automatically follows the
 *     uiSystem mode and recreates the activity when it changes.
 *
 * Stored preference: "theme_mode" in "ev_settings"
 *   "auto" → sync with the MG launcher (mechanism adapted to the firmware)
 *   "dark"  → always dark
 *   "light" → always light
 */
object ThemeHelper {

    private const val SKIN_THEME_KEY  = "SKIN_THEME_CONFIG"
    const val ACTION_SKIN_CHANGE      = "com.saicmotor.changeSkin"
    const val PREF_THEME_MODE         = "theme_mode"
    private const val PREFS_NAME      = "ev_settings"

    /**
     * Callback invoked (on the main thread) when the launcher changes theme
     * on SWI69/131/132. MainActivity uses it to trigger recreate().
     * Sur SWI133/68, AppCompat appelle recreate() automatiquement via FOLLOW_SYSTEM.
     */
    @Volatile var onThemeChanged: (() -> Unit)? = null

    // ── Mechanism detection ─────────────────────── ────────────────────────

    /**
     * Returns true if this firmware exposes SKIN_THEME_CONFIG in Settings.System
     * (SWI69 / SWI131 / SWI132). On SWI68/133, use FOLLOW_SYSTEM instead.
     */
    fun hasSkinThemeConfig(context: Context): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, SKIN_THEME_KEY, -1) != -1
        } catch (e: Exception) {
            false
        }
    }

    // ── Playing the launcher theme (SWI69/131/132 only) ─────────────────

    /**
     * Reads SKIN_THEME_CONFIG and returns MODE_NIGHT_YES or MODE_NIGHT_NO.
     * Only call if hasSkinThemeConfig() == true.
     */
    fun getLauncherNightMode(context: Context): Int {
        return try {
            val value = Settings.System.getInt(context.contentResolver, SKIN_THEME_KEY, 0)
            if (value == 1) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        } catch (e: Exception) {
            AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    // ── Resolution of the mode to apply ───────────────────────────────────────

    /**
     * Returns the AppCompat night mode to apply according to the "theme_mode" preference.
     *
     * "auto" on SWI69/131/132 → YES or NO according to SKIN_THEME_CONFIG
     * "auto" on SWI133/68 → MODE_NIGHT_FOLLOW_SYSTEM (follows Android uiMode)
     */
    fun resolveNightMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (prefs.getString(PREF_THEME_MODE, "auto")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "auto"  -> if (hasSkinThemeConfig(context)) getLauncherNightMode(context)
                       else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else    -> AppCompatDelegate.MODE_NIGHT_YES   // "dark" + fallback
        }
    }

    // ── Notification de changement (SWI69/131/132) ───────────────────────────

    /** Called from the service (already on the main thread) to trigger recreate(). */
    fun notifyThemeChanged() {
        onThemeChanged?.invoke()
    }
}
