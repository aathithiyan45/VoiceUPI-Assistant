package com.voiceupi.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TtsHelper
 *
 * Reusable TextToSpeech wrapper with a clean [speakAndThen] API.
 * Detects if language data is missing and provides a way to open settings.
 */
class TtsHelper(private val context: Context) {

    companion object {
        private const val TAG         = "TtsHelper"
        private const val UTT_ACTION  = "utt_speak_and_then"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingAction: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Flag to check if Tamil is actually available
    var isTamilSupported: Boolean = false
        private set

    /**
     * Initialise TTS with the desired language.
     * [onReady] is invoked on the main thread once TTS is ready.
     */
    fun init(isTamil: Boolean, onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS initialisation failed with status $status")
                return@TextToSpeech
            }
            applyLocale(isTamil)
            isReady = true

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error for utterance: $utteranceId")
                    mainHandler.post { pendingAction = null }
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == UTT_ACTION) {
                        mainHandler.post {
                            pendingAction?.invoke()
                            pendingAction = null
                        }
                    }
                }
            })

            mainHandler.post { onReady?.invoke() }
        }
    }

    /**
     * Apply (or switch) TTS locale at any time.
     */
    fun applyLocale(isTamil: Boolean) {
        val locale = if (isTamil) Locale("ta", "IN") else Locale("en", "IN")
        val result = tts?.setLanguage(locale) ?: return
        
        if (isTamil) {
            isTamilSupported = result != TextToSpeech.LANG_MISSING_DATA && 
                              result != TextToSpeech.LANG_NOT_SUPPORTED
            
            if (!isTamilSupported) {
                Log.w(TAG, "Tamil data missing or not supported on this engine.")
                // Fallback so it doesn't crash or stay silent
                tts?.setLanguage(Locale("en", "IN"))
            }
        }
    }

    /**
     * Opens the System TTS settings so the user can download voice data.
     */
    fun openTtsSettings() {
        val intent = Intent().apply {
            action = "com.android.settings.TTS_SETTINGS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Speak [text] and invoke [action] only after speech has fully completed.
     */
    fun speakAndThen(text: String, action: () -> Unit) {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready — executing action immediately")
            mainHandler.post(action)
            return
        }
        pendingAction = action
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTT_ACTION)
    }

    fun speak(text: String, utteranceId: String) {
        if (!isReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts    = null
        isReady = false
    }

    val ready: Boolean get() = isReady
}
