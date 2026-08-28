package com.evsuite.profile.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    private const val PREFS      = "ev_settings"
    private const val KEY_LANG   = "language"
    private const val KEY_LANG_SET = "language_set"

    /** Returns the saved language code (English by default). */
    fun getLanguage(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "en") ?: "en"
        return if (saved == "fr") "en" else saved
    }

    /** Saves the selection and marks first-launch language setup as complete. */
    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANG, lang)
            .putBoolean(KEY_LANG_SET, true)
            .apply()
    }

    /** Returns true until the user completes first-launch language setup. */
    fun isFirstLaunch(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LANG_SET, false)

    /** Applies the saved locale to the supplied context and returns the updated context. */
    fun applyLocale(context: Context): Context {
        val lang   = getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
