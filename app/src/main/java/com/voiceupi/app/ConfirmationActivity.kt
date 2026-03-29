package com.voiceupi.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    private lateinit var tvMerchant : TextView
    private lateinit var tvAmount   : TextView
    private lateinit var tvUpiId    : TextView
    private lateinit var tvStatus   : TextView

    private var merchantName = "Merchant"
    private var upiId        = ""
    private var amount       = "0"

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var retryCount  = 0
    private val MAX_RETRIES = 2

    private val handler         = Handler(Looper.getMainLooper())
    private val tag             = "ConfirmationActivity"
    private val UTT_CONFIRM     = "utt_confirm"
    private val UTT_RESULT      = "utt_result"
    private val REQ_UPI_PAYMENT = 101

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirmation)

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
        tts.shutdown()
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
        tvStatus.text   = "Awaiting confirmation…"
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TTS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("en", "IN")
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
                Log.e(tag, "TTS init failed")
            }
        }
    }

    private fun speak(text: String, id: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun speakConfirmation() {
        val msg = "You are paying ₹$amount to $merchantName. Say yes to confirm or no to cancel."
        setStatus("Say YES or NO")
        speak(msg, UTT_CONFIRM)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Speech Recognizer
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
            speak("Microphone permission required.", UTT_RESULT)
            return
        }
        if (speechRecognizer == null) initSpeechRecognizer()
        val recognizer = speechRecognizer ?: return
        isListening = true
        setStatus("Listening…")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
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
        override fun onBeginningOfSpeech() { setStatus("Listening…") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false; setStatus("Processing…") }
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) handleAnswer(matches[0].lowercase().trim())
            else onListenError()
        }
        override fun onError(error: Int) {
            isListening = false
            onListenError()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Command handling
    // ══════════════════════════════════════════════════════════════════════

    private fun handleAnswer(spoken: String) {
        Log.d(tag, "Answer: $spoken")
        when {
            isAffirmative(spoken) -> confirmPayment()
            isNegative(spoken)    -> cancelPayment()
            else -> {
                retryCount++
                if (retryCount <= MAX_RETRIES) {
                    speak("Please say yes to confirm or no to cancel.", UTT_CONFIRM)
                    setStatus("Say YES or NO")
                } else {
                    retryCount = 0
                    speak("Too many attempts. Payment cancelled.", UTT_RESULT)
                    handler.postDelayed({ goToVoiceMain() }, 2500)
                }
            }
        }
    }

    private fun onListenError() {
        retryCount++
        if (retryCount <= MAX_RETRIES) {
            speak("Didn't catch that. Say yes to confirm or no to cancel.", UTT_CONFIRM)
        } else {
            retryCount = 0
            speak("Could not hear you. Payment cancelled.", UTT_RESULT)
            handler.postDelayed({ goToVoiceMain() }, 2500)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Actions
    // ══════════════════════════════════════════════════════════════════════

    private fun confirmPayment() {
        setStatus("Confirmed! Opening payment app…")
        speak("Confirmed. Opening payment app now.", UTT_RESULT)
        handler.postDelayed({ launchUpiPayment() }, 1200)
    }

    private fun launchUpiPayment() {
        val upiUri = Uri.Builder()
            .scheme("upi")
            .authority("pay")
            .appendQueryParameter("pa", upiId)
            .appendQueryParameter("pn", merchantName)
            .appendQueryParameter("am", amount)
            .appendQueryParameter("cu", "INR")
            .appendQueryParameter("tn", "Payment via VoiceUPI")
            .build()

        Log.d(tag, "UPI Intent URI → $upiUri")

        val payIntent = Intent(Intent.ACTION_VIEW, upiUri)

        try {
            // ✅ KEY FIX: queryIntentActivities remove பண்ணி
            // directly createChooser fire பண்றோம்
            // Android 11+ ல queryIntentActivities block ஆகுது
            // ஆனா createChooser எப்பவும் work ஆகும்
            val chooser = Intent.createChooser(payIntent, "Pay ₹$amount using")
            @Suppress("DEPRECATION")
            startActivityForResult(chooser, REQ_UPI_PAYMENT)
        } catch (e: ActivityNotFoundException) {
            Log.e(tag, "ActivityNotFoundException — no UPI app", e)
            setStatus("❌ No UPI app found")
            speak(
                "No UPI payment app found on this device. Please install Google Pay or PhonePe.",
                UTT_RESULT
            )
            handler.postDelayed({ goToVoiceMain() }, 3000)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UPI Payment Result
    // ══════════════════════════════════════════════════════════════════════

    @Deprecated("Required for UPI payment result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQ_UPI_PAYMENT) return

        val dataString = data?.dataString ?: ""
        val status = extractUpiStatus(dataString, data)
        Log.d(tag, "UPI result → resultCode=$resultCode status=$status data=$dataString")

        when {
            status.contains("success", ignoreCase = true) -> {
                setStatus("✅ Payment Successful!")
                speak("Payment successful! Thank you.", UTT_RESULT)
                handler.postDelayed({
                    startActivity(Intent(this, SuccessActivity::class.java).apply {
                        putExtra(SuccessActivity.EXTRA_MERCHANT_NAME, merchantName)
                        putExtra(SuccessActivity.EXTRA_AMOUNT, amount)
                    })
                    finish()
                }, 1500)
            }
            status.contains("submitted", ignoreCase = true) -> {
                setStatus("⏳ Payment Submitted")
                speak("Payment submitted and is being processed.", UTT_RESULT)
                handler.postDelayed({
                    startActivity(Intent(this, SuccessActivity::class.java).apply {
                        putExtra(SuccessActivity.EXTRA_MERCHANT_NAME, merchantName)
                        putExtra(SuccessActivity.EXTRA_AMOUNT, amount)
                    })
                    finish()
                }, 1500)
            }
            resultCode == RESULT_CANCELED || status.isEmpty() -> {
                setStatus("Payment cancelled.")
                speak("Payment was cancelled. You can try again.", UTT_RESULT)
                handler.postDelayed({ goToVoiceMain() }, 2500)
            }
            else -> {
                setStatus("❌ Payment Failed")
                speak("Payment failed. Please check your balance or try again.", UTT_RESULT)
                handler.postDelayed({ goToVoiceMain() }, 3000)
            }
        }
    }

    private fun extractUpiStatus(dataString: String, data: Intent?): String {
        if (dataString.isNotBlank()) {
            try {
                val uri = Uri.parse(dataString)
                val status = uri.getQueryParameter("Status") ?: uri.getQueryParameter("status")
                if (!status.isNullOrBlank()) return status
            } catch (_: Exception) {}
        }
        data?.extras?.let { extras ->
            val status = extras.getString("Status") ?: extras.getString("status") ?: ""
            if (status.isNotBlank()) return status
        }
        return ""
    }

    private fun cancelPayment() {
        setStatus("Payment cancelled.")
        speak("Payment cancelled.", UTT_RESULT)
        handler.postDelayed({ goToVoiceMain() }, 1800)
    }

    private fun goToVoiceMain() {
        startActivity(Intent(this, VoiceMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun isAffirmative(text: String) =
        listOf("yes", "yeah", "yep", "confirm", "ok", "okay", "sure", "haan", "ha").any { text.contains(it) }

    private fun isNegative(text: String) =
        listOf("no", "nope", "cancel", "nahi", "back", "stop").any { text.contains(it) }

    private fun setStatus(text: String) {
        tvStatus.text = text
        tvStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }
}