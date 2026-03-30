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
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import org.json.JSONObject
import java.util.Locale

class ConfirmationActivity : AppCompatActivity(), PaymentResultListener {

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

    private val handler     = Handler(Looper.getMainLooper())
    private val tag         = "ConfirmationActivity"
    private val UTT_CONFIRM = "utt_confirm"
    private val UTT_RESULT  = "utt_result"

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

        // ✅ Razorpay preload — activity start la call pannanum for faster checkout
        Checkout.preload(applicationContext)

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
        setStatus("Confirmed! Opening payment…")
        speak("Confirmed. Opening payment now.", UTT_RESULT)
        handler.postDelayed({ startRazorpayPayment() }, 1200)
    }

    private fun startRazorpayPayment() {
        val checkout = Checkout()
        checkout.setKeyID("rzp_test_SXTv40eyMIVyLW") // 🔑 Replace with your actual Razorpay Key ID

        try {
            val options = JSONObject().apply {
                put("name", merchantName)
                put("description", "VoiceUPI Payment")
                put("currency", "INR")

                // ✅ Razorpay needs amount in paise (multiply by 100)
                val amountInPaise = ((amount.toDoubleOrNull() ?: 1.0) * 100).toInt()
                put("amount", amountInPaise)

                // ✅ Prefill user details (replace with real user data if available)
                val prefill = JSONObject().apply {
                    put("email", "user@example.com")
                    put("contact", "9999999999")
                }
                put("prefill", prefill)

                // ✅ Optional: restrict to UPI only if needed
                // val config = JSONObject()
                // val display = JSONObject()
                // val blocks = JSONObject()
                // val utib = JSONObject()
                // utib.put("name", "Pay via UPI")
                // utib.put("instruments", JSONArray().put(JSONObject().put("method", "upi")))
                // blocks.put("utib", utib)
                // display.put("blocks", blocks)
                // display.put("sequence", JSONArray().put("block.utib"))
                // display.put("preferences", JSONObject().put("show_default_blocks", false))
                // config.put("display", display)
                // put("config", config)
            }

            checkout.open(this, options)

        } catch (e: Exception) {
            Log.e(tag, "Razorpay open failed", e)
            speak("Payment failed to start. Please try again.", UTT_RESULT)
            handler.postDelayed({ goToVoiceMain() }, 2500)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Razorpay Payment Result (PaymentResultListener)
    // ══════════════════════════════════════════════════════════════════════

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        Log.d(tag, "Payment Success — ID: $razorpayPaymentId")
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

    override fun onPaymentError(errorCode: Int, errorDescription: String?) {
        Log.e(tag, "Payment Error — code: $errorCode desc: $errorDescription")
        setStatus("❌ Payment Failed")
        speak("Payment failed. Please try again.", UTT_RESULT)
        handler.postDelayed({ goToVoiceMain() }, 2500)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Cancel
    // ══════════════════════════════════════════════════════════════════════

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