package com.voiceupi.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import java.util.Locale

class VoiceMainActivity : AppCompatActivity() {

    // ── Locale ────────────────────────────────────────────────────────────────
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var isTamil          = false
    private var languageSelected = false
    private var tamilTtsAvailable= false

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvStatus  : TextView
    private lateinit var btnMic    : ImageButton
    private lateinit var ivMicRing : ImageView
    private lateinit var cardStatus: CardView

    // ── TTS ───────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady     = false
    private var pendingAction: (() -> Unit)? = null

    // ── ASR ───────────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── Vibration ─────────────────────────────────────────────────────────────
    private var vibrator: Vibrator? = null

    private val handler = Handler(Looper.getMainLooper())
    private val TAG     = "VoiceMainActivity"

    private var pendingAction2: (() -> Unit)? = null

    private val UTT_LANG_ASK = "utt_lang_ask"
    private val UTT_WELCOME  = "utt_welcome"
    private val UTT_PROMPT   = "utt_prompt"
    private val UTT_ACTION   = "utt_action"

    // ── Strings ───────────────────────────────────────────────────────────────
    private val str_lang_ask    = "Do you want Tamil or English? Say Tamil or English."
    private val str_lang_status = "Say: Tamil or English"
    private val str_lang_retry  = "Sorry, say Tamil or English please."

    private val str_welcome get() = if (isTamil)
        "வணக்கம்! Voice UPI-க்கு வரவேற்கிறோம். QR ஸ்கேன் செய்ய ஸ்கேன் என்று சொல்லுங்கள்."
    else "Welcome to Voice UPI. Say scan QR to begin."

    private val str_prompt get() = if (isTamil)
        "ஸ்கேன், பணம் அனுப்பு, அல்லது உதவி என்று சொல்லுங்கள்."
    else "Say scan QR, send money, or help."

    private val str_status_listening  get() = if (isTamil) "கேட்கிறேன்…"         else "Listening…"
    private val str_status_processing get() = if (isTamil) "புரிந்துகொள்கிறேன்…" else "Processing…"
    private val str_status_init       get() = if (isTamil) "தொடங்குகிறேன்…"      else "Initialising…"

    private val str_opening_scanner        get() = if (isTamil) "QR scanner திறக்கிறேன்."  else "Opening QR scanner."
    private val str_opening_scanner_status get() = if (isTamil) "QR scanner திறக்கிறது…"   else "Opening QR scanner…"
    private val str_send_soon              get() = if (isTamil) "பணம் அனுப்பும் திறன் விரைவில் வரும்." else "Send money feature is coming soon."
    private val str_send_soon_status       get() = if (isTamil) "விரைவில் வரும்."           else "Feature coming soon."

    private val str_help_tts get() = if (isTamil)
        "நீங்கள் சொல்லலாம்: QR ஸ்கேன் செய்ய ஸ்கேன் என்று சொல்லுங்கள். பணம் அனுப்ப, பணம் அனுப்பு என்று சொல்லுங்கள்."
    else "You can say: scan QR to scan a payment code, send money to transfer funds, or help to hear this again."

    private val str_help_status get() = if (isTamil)
        "கட்டளைகள்:\n• ஸ்கேன் (QR)\n• பணம் அனுப்பு\n• உதவி"
    else "Available commands:\n• Scan QR\n• Send Money\n• Help"

    private val str_not_understood get() = if (isTamil)
        "புரியவில்லை. ஸ்கேன், பணம் அனுப்பு, அல்லது உதவி சொல்லுங்கள்."
    else "I did not understand. Say scan QR, send money, or help."

    private val str_not_understood_status get() = if (isTamil)
        "கட்டளை புரியவில்லை.\nஉதவி என்று சொல்லுங்கள்."
    else "Command not recognised.\nSay help for options."

    private val str_mic_idle   get() = if (isTamil) "மைக். பேச தட்டவும்."           else "Microphone. Tap to speak."
    private val str_mic_active get() = if (isTamil) "கேட்கிறேன். நிறுத்த தட்டவும்." else "Listening. Tap to stop."
    private val str_no_speech  get() = if (isTamil) "பேச்சு கேட்கவில்லை. மீண்டும் முயற்சிக்கவும்." else "No speech detected. Try again."

    private val asrLocale get() = if (languageSelected && isTamil) "ta-IN" else "en-IN"

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_main)

        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator

        tvStatus   = findViewById(R.id.tvStatus)
        btnMic     = findViewById(R.id.btnMic)
        ivMicRing  = findViewById(R.id.ivMicRing)
        cardStatus = findViewById(R.id.cardStatus)

        // Entry animation
        cardStatus.alpha = 0f
        cardStatus.animate().alpha(1f).setDuration(500).setStartDelay(200).start()

        setStatus(str_lang_status)
        setupTts()
        setupMicButton()
        checkAudioPermission()
    }

    override fun onResume() {
        super.onResume()
        if (languageSelected && ttsReady && !isListening) {
            handler.postDelayed({ speakPrompt() }, 600)
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
        if (::tts.isInitialized) tts.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        if (::tts.isInitialized) tts.shutdown()
        speechRecognizer?.destroy()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) initSpeechRecognizer()
            else setStatus("Microphone permission denied. Please grant it in Settings.")
        }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) initSpeechRecognizer()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale("en", "IN"))
                ttsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            when (utteranceId) {
                                UTT_LANG_ASK -> startListening()
                                UTT_WELCOME  -> startListening()
                                UTT_PROMPT   -> startListening()
                                UTT_ACTION   -> {
                                    pendingAction?.invoke()
                                    pendingAction = null
                                }
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { pendingAction = null }
                    }
                })
                speak(str_lang_ask, UTT_LANG_ASK)
                setStatus(str_lang_status)
            } else {
                Log.e(TAG, "TTS init failed: $status")
                setStatus("Text-to-speech unavailable.")
            }
        }
    }

    private fun applyTtsLocale() {
        if (isTamil) {
            val result = tts.setLanguage(Locale("ta", "IN"))
            tamilTtsAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!tamilTtsAvailable) {
                Log.w(TAG, "Tamil TTS missing — falling back to en-IN")
                tts.setLanguage(Locale("en", "IN"))
            }
        } else {
            tts.setLanguage(Locale("en", "IN"))
        }
    }

    private fun speak(text: String, utteranceId: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun speakAndThen(text: String, action: () -> Unit) {
        if (!ttsReady) { action(); return }
        pendingAction = action
        speak(text, UTT_ACTION)
    }

    private fun speakPrompt() {
        setStatus(str_prompt)
        speak(str_prompt, UTT_PROMPT)
    }

    // ── Mic button ────────────────────────────────────────────────────────────

    private fun setupMicButton() {
        btnMic.contentDescription = str_mic_idle
        btnMic.setOnClickListener {
            haptic(longArrayOf(0, 30))
            if (::tts.isInitialized) tts.stop()
            if (isListening) { stopListening(); speakPrompt() } else startListening()
        }
    }

    // ── Mic ring animation ────────────────────────────────────────────────────

    private fun showMicRing(show: Boolean) {
        if (show) {
            ivMicRing.visibility = View.VISIBLE
            val anim = AnimationUtils.loadAnimation(this, R.anim.mic_ring_pulse)
            ivMicRing.startAnimation(anim)
            ivMicRing.animate().alpha(1f).setDuration(200).start()
        } else {
            ivMicRing.animate().alpha(0f).setDuration(300).withEndAction {
                ivMicRing.clearAnimation()
                ivMicRing.visibility = View.INVISIBLE
            }.start()
        }
    }

    // ── Speech recognizer ─────────────────────────────────────────────────────

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition not available."); return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    private fun startListening() {
        if (isListening) return
        val recognizer = speechRecognizer ?: run { initSpeechRecognizer(); speechRecognizer } ?: return
        isListening = true
        setStatus(str_status_listening)
        setMicActive(true)
        haptic(longArrayOf(0, 40))

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, asrLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, asrLocale)
            putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        recognizer.startListening(intent)
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        setMicActive(false)
        speechRecognizer?.stopListening()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { setStatus(str_status_listening) }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isListening = false; setMicActive(false); setStatus(str_status_processing)
        }
        override fun onResults(results: Bundle?) {
            isListening = false; setMicActive(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val spoken = matches[0].trim()
                if (!languageSelected) handleLanguageSelection(spoken)
                else handleVoiceCommand(spoken.lowercase())
            } else {
                onVoiceError(str_no_speech)
            }
        }
        override fun onError(error: Int) {
            isListening = false; setMicActive(false)
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (isTamil) "கேட்கவில்லை. மீண்டும் முயற்சிக்கவும்." else "Didn't catch that. Try again."
                SpeechRecognizer.ERROR_AUDIO ->
                    if (isTamil) "ஆடியோ பிழை. மைக் தட்டவும்." else "Audio error. Tap mic to retry."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error. Check connection."
                else -> if (isTamil) "புரியவில்லை. மைக் தட்டவும்." else "Could not understand. Tap mic to retry."
            }
            onVoiceError(msg)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Language + command handling ───────────────────────────────────────────

    private fun handleLanguageSelection(spoken: String) {
        val lower = spoken.lowercase()
        Log.d(TAG, "Language selection heard: $lower")
        isTamil = lower.contains("tamil") || lower.contains("தமிழ்") ||
                lower.contains("tamizh") || lower.contains("thamizh")
        val wantsEnglish = lower.contains("english") || lower.contains("inglish") ||
                lower.contains("ஆங்கிலம்")
        if (!isTamil && !wantsEnglish) {
            speak(str_lang_retry, UTT_LANG_ASK)
            setStatus(str_lang_status)
            return
        }
        languageSelected = true
        val langCode = if (isTamil) LocaleHelper.LANG_TAMIL else LocaleHelper.LANG_ENGLISH
        LocaleHelper.saveLanguage(this, langCode)
        LocaleHelper.setAppLocale(langCode)
        applyTtsLocale()
        speak(str_welcome, UTT_WELCOME)
        setStatus(str_status_init)
    }

    private fun handleVoiceCommand(lower: String) {
        when {
            isScanCommand(lower) -> {
                setStatus(str_opening_scanner_status)
                haptic(longArrayOf(0, 30, 50, 30))
                speakAndThen(str_opening_scanner) { openQrScanner() }
            }
            isSendCommand(lower) -> {
                setStatus(str_send_soon_status)
                speakAndThen(str_send_soon) { speakPrompt() }
            }
            isHelpCommand(lower) -> {
                setStatus(str_help_status)
                speakAndThen(str_help_tts) { speakPrompt() }
            }
            else -> {
                setStatus(str_not_understood_status)
                // Shake the status card on unrecognised command
                cardStatus.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shake))
                haptic(longArrayOf(0, 80, 60, 80))
                speakAndThen(str_not_understood) { startListening() }
            }
        }
    }

    private fun isScanCommand(lower: String) = containsAny(lower,
        "ஸ்கேன்", "ஸ்கேன் செய்", "qr காட்டு", "qr பார்", "qr ஸ்கேன்",
        "skaan", "scan pannu", "scan", "qr", "scanner")

    private fun isSendCommand(lower: String) = containsAny(lower,
        "பணம் அனுப்பு", "பணம் அனுப்", "பண அனுப்பு", "பணம் கொடு",
        "panam anuppu", "panam", "anuppu",
        "send pannu", "send money", "send", "pay", "payment", "transfer")

    private fun isHelpCommand(lower: String) = containsAny(lower,
        "உதவி", "உதவி செய்", "என்ன சொல்லலாம்",
        "utavi", "udavi", "help", "commands", "what can you do")

    private fun containsAny(input: String, vararg keywords: String) =
        keywords.any { input.contains(it) }

    private fun onVoiceError(message: String) {
        setStatus(message)
        speakAndThen(message) {
            if (!languageSelected) speak(str_lang_ask, UTT_LANG_ASK)
            else speakPrompt()
        }
    }

    private fun openQrScanner() {
        startActivity(Intent(this, QRScannerActivity::class.java).apply {
            putExtra("IS_TAMIL", isTamil)
        })
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setStatus(text: String) {
        runOnUiThread {
            tvStatus.text = text
            tvStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
    }

    private fun setMicActive(active: Boolean) {
        btnMic.isSelected       = active
        btnMic.alpha            = if (active) 1.0f else 0.85f
        btnMic.contentDescription = if (active) str_mic_active else str_mic_idle
        showMicRing(active)
        if (active) {
            val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
            btnMic.startAnimation(pulse)
        } else {
            btnMic.clearAnimation()
        }
    }

    // ── Haptic ────────────────────────────────────────────────────────────────

    private fun haptic(pattern: LongArray) {
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }
}