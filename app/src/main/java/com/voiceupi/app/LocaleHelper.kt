package com.voiceupi.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * LocaleHelper
 *
 * Modern, Samsung-safe locale management.
 * - Uses AppCompatDelegate.setApplicationLocales (API-agnostic, no deprecated updateConfiguration).
 * - Falls back to manual Context wrapping for Android < 13 or when AppCompat delegate is insufficient.
 * - Persists the choice in SharedPreferences so it survives process death.
 * - Call [applyLocale] in every Activity's attachBaseContext.
 */
object LocaleHelper {

    private const val PREFS_NAME = "voiceupi_prefs"
    private const val KEY_LANG   = "selected_language"

    const val LANG_ENGLISH = "en"
    const val LANG_TAMIL   = "ta"

    // ── Persistence ──────────────────────────────────────────────────────────

    fun saveLanguage(context: Context, langCode: String) {
        prefs(context).edit().putString(KEY_LANG, langCode).apply()
    }

    fun getSavedLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, LANG_ENGLISH) ?: LANG_ENGLISH

    // ── AppCompat delegate (Android 13+ path + back-ported via AppCompat) ──

    /**
     * Call this from Application.onCreate() and from the Activity that lets the
     * user pick a language (after saving the choice).
     *
     * AppCompatDelegate.setApplicationLocales is the Google-recommended way and
     * works on Android 4+ via AppCompat back-compat layer.
     */
    fun setAppLocale(langCode: String) {
        val localeList = LocaleListCompat.forLanguageTags(
            if (langCode == LANG_TAMIL) "ta-IN" else "en-IN"
        )
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    // ── Context wrapping (used in attachBaseContext for Samsung & MIUI) ──────

    /**
     * Wrap [context] with the saved locale.
     * Call this inside every Activity.attachBaseContext:
     *
     *   override fun attachBaseContext(newBase: Context) {
     *       super.attachBaseContext(LocaleHelper.applyLocale(newBase))
     *   }
     */
    fun applyLocale(context: Context): Context {
        val langCode = getSavedLanguage(context)
        return wrapContext(context, langCode)
    }

    /**
     * Wrap [context] with an explicit [langCode].
     * Useful when you already know the language without reading from prefs.
     */
    fun applyLocale(context: Context, langCode: String): Context {
        saveLanguage(context, langCode)
        return wrapContext(context, langCode)
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun wrapContext(context: Context, langCode: String): Context {
        val locale = if (langCode == LANG_TAMIL) Locale("ta", "IN") else Locale("en", "IN")
        Locale.setDefault(locale)

        val config = context.resources.configuration

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // API 24+: use LocaleList
            val localeList = LocaleList(locale)
            config.setLocales(localeList)
            config.setLocale(locale)
            context.createConfigurationContext(config)
        } else {
            // API < 24: set single locale
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}