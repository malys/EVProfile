package com.evsuite.profile.util

import android.content.Context
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate

/**
 * Gestion du thème de l'application (sombre / clair / auto-sync launcher).
 *
 * Le mode "auto" est disponible sur TOUS les firmwares, mais fonctionne différemment :
 *
 * ─ SWI69 / SWI131 / SWI132 ──────────────────────────────────────────────────────
 *   Clé Settings.System : "SKIN_THEME_CONFIG"  (0 = sombre, 1 = clair)
 *   Broadcast launcher  : "com.saicmotor.changeSkin" (sans extra → relire Settings.System)
 *   → On lit la valeur explicitement et on passe MODE_NIGHT_YES/NO + recreate().
 *
 * ─ SWI133 / SWI68 ───────────────────────────────────────────────────────────────
 *   Le launcher appelle UiModeManager.setNightMode() qui change le uiMode Android
 *   global (0x13 = clair, 0x23 = sombre). Le SkinManager SWI133 lit isNightMode()
 *   depuis Configuration.uiMode.
 *   → On utilise MODE_NIGHT_FOLLOW_SYSTEM : AppCompat suit automatiquement le
 *     uiMode système et recrée l'activité lorsqu'il change.
 *
 * Préférence stockée : "theme_mode" dans "ev_settings"
 *   "auto"  → sync avec le launcher MG (mécanisme adapté au firmware)
 *   "dark"  → toujours sombre
 *   "light" → toujours clair
 */
object ThemeHelper {

    private const val SKIN_THEME_KEY  = "SKIN_THEME_CONFIG"
    const val ACTION_SKIN_CHANGE      = "com.saicmotor.changeSkin"
    const val PREF_THEME_MODE         = "theme_mode"
    private const val PREFS_NAME      = "ev_settings"

    /**
     * Callback invoqué (sur le thread principal) lorsque le launcher change de thème
     * sur SWI69/131/132. MainActivity l'utilise pour déclencher recreate().
     * Sur SWI133/68, AppCompat appelle recreate() automatiquement via FOLLOW_SYSTEM.
     */
    @Volatile var onThemeChanged: (() -> Unit)? = null

    // ── Détection du mécanisme ───────────────────────────────────────────────

    /**
     * Retourne true si ce firmware expose SKIN_THEME_CONFIG dans Settings.System
     * (SWI69 / SWI131 / SWI132). Sur SWI68/133, utilise FOLLOW_SYSTEM à la place.
     */
    fun hasSkinThemeConfig(context: Context): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, SKIN_THEME_KEY, -1) != -1
        } catch (e: Exception) {
            false
        }
    }

    // ── Lecture du thème launcher (SWI69/131/132 uniquement) ─────────────────

    /**
     * Lit SKIN_THEME_CONFIG et retourne MODE_NIGHT_YES ou MODE_NIGHT_NO.
     * À n'appeler que si hasSkinThemeConfig() == true.
     */
    fun getLauncherNightMode(context: Context): Int {
        return try {
            val value = Settings.System.getInt(context.contentResolver, SKIN_THEME_KEY, 0)
            if (value == 1) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        } catch (e: Exception) {
            AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    // ── Résolution du mode à appliquer ───────────────────────────────────────

    /**
     * Retourne le night mode AppCompat à appliquer selon la préférence "theme_mode".
     *
     * "auto" sur SWI69/131/132 → YES ou NO selon SKIN_THEME_CONFIG
     * "auto" sur SWI133/68    → MODE_NIGHT_FOLLOW_SYSTEM (suit le uiMode Android)
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

    /** Appelé depuis le service (déjà sur le main thread) pour déclencher recreate(). */
    fun notifyThemeChanged() {
        onThemeChanged?.invoke()
    }
}
