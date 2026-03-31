package com.voiceupi.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TtsHelper
 *
 * Reusable TextToSpeech wrapper with a clean [speakAndThen] API that
 * guarantees navigation / actions happen ONLY after speech finishes.
 *
 * Usage:
 *   private val ttsHelper = TtsHelper(this)
 *
 *   // In onCreate or wherever you initialise TTS:
 *   ttsHelper.init(isTamil = true) { /* called once TTS is ready */ }
 *
 *   // To speak then navigate (no Handler.postDelayed needed):
 *   ttsHelper.speakAndThen("Confirmed. Opening next screen.") {
 *       startActivity(Intent(this, NextActivity::class.java))
 *   }
 *
 *   // In onDestroy:
 *   ttsHelper.shutdown()
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
     * Safe to call before or after [init].
     */
    fun applyLocale(isTamil: Boolean) {
        val locale = if (isTamil) Locale("ta", "IN") else Locale("en", "IN")
        val result = tts?.setLanguage(locale) ?: return
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Locale $locale not supported — falling back to en-IN")
            tts?.setLanguage(Locale("en", "IN"))
        }
    }

    /**
     * Speak [text] and invoke [action] only after speech has fully completed.
     * Uses UtteranceProgressListener.onDone — no Handler.postDelayed.
     *
     * If TTS is not ready, [action] is called immediately so the app never hangs.
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

    /**
     * Speak [text] with a custom [utteranceId].
     * You manage the UtteranceProgressListener yourself in this case.
     */
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