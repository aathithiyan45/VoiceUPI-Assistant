package com.voiceupi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class ConfirmationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MERCHANT_NAME = "extra_merchant_name"
        const val EXTRA_UPI_ID        = "extra_upi_id"
        const val EXTRA_AMOUNT        = "extra_amount"
    }

    // ── Language ───────────────────────────────────────────────────────────
    private var isTamil = false

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var tvMerchant : TextView
    private lateinit var tvAmount   : TextView
    private lateinit var tvUpiId    : TextView
    private lateinit var tvStatus   : TextView

    // ── Payment data ───────────────────────────────────────────────────────
    private var merchantName = "Merchant"
    private var upiId        = ""
    private var amount       = "0"

    // ── TTS ────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    // ── ASR ────────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var retryCount  = 0
    private val MAX_RETRIES = 2

    // ── Internal ───────────────────────────────────────────────────────────
    private val handler     = Handler(Looper.getMainLooper())
    private val TAG         = "ConfirmationActivity"
    private val UTT_CONFIRM = "utt_confirm"
    private val UTT_RESULT  = "utt_result"

    // ══════════════════════════════════════════════════════════════════════
    //  Localised strings
    // ══════════════════════════════════════════════════════════════════════

    private val str_confirm_tts get() = if (isTamil)
        "நீங்கள் $merchantName-க்கு ₹$amount அனுப்புகிறீர்கள். " +
                "உறுதிப்படுத்த ஆம் என்று சொல்லுங்கள். ரத்து செய்ய இல்லை என்று சொல்லுங்கள்."
    else
        "You are paying ₹$amount to $merchantName. Say yes to confirm or no to cancel."

    private val str_say_yes_no get() = if (isTamil) "ஆம் அல்லது இல்லை சொல்லுங்கள்" else "Say YES or NO"
    private val str_listening  get() = if (isTamil) "கேட்கிறேன்…"                    else "Listening…"
    private val str_processing get() = if (isTamil) "புரிந்துகொள்கிறேன்…"            else "Processing…"

    private val str_retry_tts get() = if (isTamil)
        "ஆம் என்று உறுதிப்படுத்தவும், இல்லை என்று ரத்து செய்யவும்."
    else "Please say yes to confirm or no to cancel."

    private val str_no_hear_tts get() = if (isTamil)
        "கேட்கவில்லை. ஆம் அல்லது இல்லை சொல்லுங்கள்."
    else "Didn't catch that. Say yes to confirm or no to cancel."

    private val str_too_many_tts get() = if (isTamil)
        "பல முயற்சிகள் தோல்வி. பணம் ரத்து செய்யப்பட்டது."
    else "Too many attempts. Payment cancelled."

    private val str_no_hear_cancel_tts get() = if (isTamil)
        "கேட்கவில்லை. பணம் ரத்து செய்யப்பட்டது."
    else "Could not hear you. Payment cancelled."

    private val str_confirmed_status get() = if (isTamil)
        "உறுதிப்படுத்தப்பட்டது! Google Pay திறக்கிறது…"
    else "Confirmed! Opening Google Pay…"

    private val str_confirmed_tts get() = if (isTamil)
        "உறுதிப்படுத்தப்பட்டது. Google Pay திறக்கிறது."
    else "Confirmed. Opening Google Pay."

    private val str_cancelled_status get() = if (isTamil) "பணம் ரத்து செய்யப்பட்டது." else "Payment cancelled."
    private val str_cancelled_tts    get() = if (isTamil) "பணம் ரத்து செய்யப்பட்டது." else "Payment cancelled."

    private val str_mic_denied_tts get() = if (isTamil)
        "மைக்ரோஃபோன் அனுமதி தேவை."
    else "Microphone permission required."

    private val str_awaiting get() = if (isTamil) "உறுதிப்படுத்தல் எதிர்பார்க்கிறோம்…" else "Awaiting confirmation…"

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirmation)

        // ✅ Read IS_TAMIL from QRScannerActivity
        isTamil = intent.getBooleanExtra("IS_TAMIL", false)
        Log.d(TAG, "ConfirmationActivity isTamil=$isTamil")

        tvMerchant = findViewById(R.id.tvMerchant)
        tvAmount   = findViewById(R.id.tvAmount)
        tvUpiId    = findViewById(R.id.tvUpiId)
        tvStatus   = findViewById(R.id.tvStatus)

        readExtras()
        populateUi()
        setupTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        if (::tts.isInitialized) tts.shutdown()
        speechRecognizer?.destroy()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Data
    // ══════════════════════════════════════════════════════════════════════

    private fun readExtras() {
        merchantName = intent.getStringExtra(EXTRA_MERCHANT_NAME) ?: "Merchant"
        upiId        = intent.getStringExtra(EXTRA_UPI_ID)        ?: ""
        amount       = intent.getStringExtra(EXTRA_AMOUNT)        ?: "0"
    }

    private fun populateUi() {
        tvMerchant.text = merchantName
        tvAmount.text   = "₹$amount"
        tvUpiId.text    = upiId.ifBlank { "—" }
        setStatus(str_awaiting)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TTS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyTtsLocale()
                ttsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            if (utteranceId == UTT_CONFIRM) startListening()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                speakConfirmation()
            } else {
                Log.e(TAG, "TTS init failed")
            }
        }
    }

    private fun applyTtsLocale() {
        if (isTamil) {
            val result = tts.setLanguage(Locale("ta", "IN"))
            val tamilOk = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!tamilOk) {
                Log.w(TAG, "Tamil TTS unavailable — falling back to en-IN")
                tts.setLanguage(Locale("en", "IN"))
            }
        } else {
            tts.setLanguage(Locale("en", "IN"))
        }
    }

    private fun speak(text: String, id: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun speakConfirmation() {
        setStatus(str_say_yes_no)
        speak(str_confirm_tts, UTT_CONFIRM)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ASR
    // ══════════════════════════════════════════════════════════════════════

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    private fun startListening() {
        if (isListening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            speak(str_mic_denied_tts, UTT_RESULT)
            return
        }
        if (speechRecognizer == null) initSpeechRecognizer()
        val recognizer = speechRecognizer ?: return
        isListening = true
        setStatus(str_listening)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Always en-IN — stable. Tamil yes/no detected via keyword matching below.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        recognizer.startListening(intent)
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        speechRecognizer?.stopListening()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { setStatus(str_listening) }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false; setStatus(str_processing) }
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Voice answer candidates: $matches")
                handleAnswer(matches[0].lowercase().trim())
            } else {
                onListenError()
            }
        }
        override fun onError(error: Int) {
            isListening = false
            Log.w(TAG, "ASR error: $error")
            onListenError()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Answer handling
    // ══════════════════════════════════════════════════════════════════════

    private fun handleAnswer(spoken: String) {
        Log.d(TAG, "Answer heard: $spoken [isTamil=$isTamil]")
        when {
            isAffirmative(spoken) -> confirmPayment()
            isNegative(spoken)    -> cancelPayment()
            else -> {
                retryCount++
                if (retryCount <= MAX_RETRIES) {
                    speak(str_retry_tts, UTT_CONFIRM)
                    setStatus(str_say_yes_no)
                } else {
                    retryCount = 0
                    speak(str_too_many_tts, UTT_RESULT)
                    setStatus(str_cancelled_status)
                    handler.postDelayed({ goToVoiceMain() }, 2500)
                }
            }
        }
    }

    private fun onListenError() {
        retryCount++
        if (retryCount <= MAX_RETRIES) {
            speak(str_no_hear_tts, UTT_CONFIRM)
            setStatus(str_say_yes_no)
        } else {
            retryCount = 0
            speak(str_no_hear_cancel_tts, UTT_RESULT)
            setStatus(str_cancelled_status)
            handler.postDelayed({ goToVoiceMain() }, 2500)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Yes / No detection — English + Tamil + Tanglish
    //  (en-IN engine outputs Tamil words as transliterations)
    // ══════════════════════════════════════════════════════════════════════

    private fun isAffirmative(text: String) = listOf(
        // English
        "yes", "yeah", "yep", "yup", "confirm", "ok", "okay", "sure", "correct", "proceed",
        // Hinglish (common on Indian devices)
        "haan", "ha", "bilkul",
        // Tamil via en-IN transliteration
        "aam", "aama", "sari", "saari", "confirm pannu", "send pannu",
        // Tamil script (sometimes passes through)
        "ஆம்", "சரி", "ஓகே"
    ).any { text.contains(it) }

    private fun isNegative(text: String) = listOf(
        // English
        "no", "nope", "cancel", "stop", "back", "reject", "don't", "dont",
        // Hinglish
        "nahi", "nai", "mat",
        // Tamil via en-IN transliteration
        "illai", "illa", "venda", "cancel pannu", "nirthu",
        // Tamil script
        "இல்லை", "வேண்டாம்", "நிறுத்து"
    ).any { text.contains(it) }

    // ══════════════════════════════════════════════════════════════════════
    //  Payment actions
    // ══════════════════════════════════════════════════════════════════════

    private fun confirmPayment() {
        setStatus(str_confirmed_status)
        speak(str_confirmed_tts, UTT_RESULT)
        handler.postDelayed({
            startActivity(Intent(this, FakeGPayActivity::class.java).apply {
                putExtra(FakeGPayActivity.EXTRA_MERCHANT_NAME, merchantName)
                putExtra(FakeGPayActivity.EXTRA_UPI_ID, upiId)
                putExtra(FakeGPayActivity.EXTRA_AMOUNT, amount)
                putExtra("IS_TAMIL", isTamil)   // pass language forward
            })
            finish()
        }, 1200)
    }

    private fun cancelPayment() {
        setStatus(str_cancelled_status)
        speak(str_cancelled_tts, UTT_RESULT)
        handler.postDelayed({ goToVoiceMain() }, 1800)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Navigation
    // ══════════════════════════════════════════════════════════════════════

    private fun goToVoiceMain() {
        startActivity(Intent(this, VoiceMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun setStatus(text: String) {
        tvStatus.text = text
        tvStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }
}