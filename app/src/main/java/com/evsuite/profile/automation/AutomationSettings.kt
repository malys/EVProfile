package com.evsuite.profile.automation

import android.content.Context

/** Clés + defaults de l'automatisation température, partagés entre l'UI et le service. */
object AutomationSettings {

    const val PREFS            = "ev_settings"
    const val KEY_ENABLED      = "automation_temp_enabled"
    const val KEY_THRESHOLD    = "automation_temp_threshold"
    const val KEY_PROFILE_ID   = "automation_temp_profile_id"
    const val KEY_AUTO_EXECUTE = "automation_temp_auto_execute"
    const val KEY_DIRECTION    = "automation_temp_direction"

    const val DEFAULT_THRESHOLD = 25
    const val MIN_TEMP = 0
    const val MAX_TEMP = 60

    /** Sens du déclenchement : sous le seuil (froid) ou au-dessus (chaud). */
    enum class Direction { BELOW, ABOVE }

    data class Config(
        val enabled: Boolean,
        val threshold: Int,
        val profileId: String,
        val autoExecute: Boolean,
        val direction: Direction
    )

    fun read(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled     = p.getBoolean(KEY_ENABLED, false),
            threshold   = p.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD),
            profileId   = p.getString(KEY_PROFILE_ID, "") ?: "",
            autoExecute = p.getBoolean(KEY_AUTO_EXECUTE, false),
            direction   = readDirection(context)
        )
    }

    /** Direction persistée, repli BELOW si absente/invalide. */
    fun readDirection(context: Context): Direction {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DIRECTION, Direction.BELOW.name) ?: Direction.BELOW.name
        return runCatching { Direction.valueOf(raw) }.getOrDefault(Direction.BELOW)
    }

    /** Clampe une saisie de seuil dans [MIN_TEMP, MAX_TEMP] ; null/vide => défaut. */
    fun clampTemp(raw: Int?): Int = (raw ?: DEFAULT_THRESHOLD).coerceIn(MIN_TEMP, MAX_TEMP)
}
