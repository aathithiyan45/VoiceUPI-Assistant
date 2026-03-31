package com.voiceupi.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application class.
 *
 * Registers AppCompatDelegate as the default night-mode controller and,
 * critically, re-applies the saved locale before any Activity is created.
 * This is the recommended entry-point for locale setup (Android docs).
 *
 * Register in AndroidManifest.xml:
 *   <application android:name=".VoiceUpiApplication" ...>
 */
class VoiceUpiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Re-apply the persisted locale via AppCompat delegate.
        // This handles Android 13+ natively and uses AppCompat's back-compat
        // layer on older versions — no deprecated updateConfiguration needed.
        val savedLang = LocaleHelper.getSavedLanguage(this)
        LocaleHelper.setAppLocale(savedLang)
    }
}