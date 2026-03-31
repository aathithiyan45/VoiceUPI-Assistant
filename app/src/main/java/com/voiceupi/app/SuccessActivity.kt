package com.voiceupi.app

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import java.util.Locale

class SuccessActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MERCHANT_NAME = "extra_merchant_name"
        const val EXTRA_AMOUNT        = "extra_amount"
    }

    // ── Locale ────────────────────────────────────────────────────────────────
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    // ── Language ──────────────────────────────────────────────────────────────
    private var isTamil = false

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvSuccessTitle   : TextView
    private lateinit var tvPaymentDetails : TextView
    private lateinit var tvCountdown      : TextView
    private lateinit var ivSuccessTick    : ImageView
    private lateinit var bgGlow           : View
    private lateinit var cardSuccessDetails: CardView

    // ── Data ──────────────────────────────────────────────────────────────────
    private var merchant = "Merchant"
    private var amount   = "0"

    // ── TTS & sound ───────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady    = false
    private var mediaPlayer: MediaPlayer? = null

    // ── Vibration ─────────────────────────────────────────────────────────────
    private var vibrator: Vibrator? = null

    private val handler     = Handler(Looper.getMainLooper())
    private val TAG         = "SuccessActivity"
    private val UTT_SUCCESS = "utt_success"
    private var secondsLeft = 4

    // ── Strings ───────────────────────────────────────────────────────────────

    private val str_success_title get() = if (isTamil) "பணம் அனுப்பப்பட்டது! ✅" else "Payment Successful! ✅"

    private val str_payment_details get() = if (isTamil)
        "₹$amount — $merchant-க்கு செலுத்தப்பட்டது"
    else "₹$amount paid to $merchant"

    private val str_success_tts get() = if (isTamil)
        "$merchant-க்கு ₹$amount பணம் வெற்றிகரமாக அனுப்பப்பட்டது."
    else "Payment of ₹$amount to $merchant was successful."

    private val str_countdown get() = if (isTamil)
        "முதல் திரைக்கு $secondsLeft விநாடியில் திரும்புகிறோம்…"
    else "Returning in $secondsLeft…"

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_success)

        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator

        isTamil  = intent.getBooleanExtra("IS_TAMIL", false)
        merchant = intent.getStringExtra(EXTRA_MERCHANT_NAME) ?: "Merchant"
        amount   = intent.getStringExtra(EXTRA_AMOUNT)        ?: "0"
        Log.d(TAG, "SuccessActivity isTamil=$isTamil merchant=$merchant amount=$amount")

        tvSuccessTitle    = findViewById(R.id.tvSuccessTitle)
        tvPaymentDetails  = findViewById(R.id.tvPaymentDetails)
        tvCountdown       = findViewById(R.id.tvCountdown)
        ivSuccessTick     = findViewById(R.id.ivSuccessTick)
        bgGlow            = findViewById(R.id.bgGlow)
        cardSuccessDetails= findViewById(R.id.cardSuccessDetails)

        tvSuccessTitle.text   = str_success_title
        tvPaymentDetails.text = str_payment_details

        runEntryAnimations()
        playSuccessSound()
        celebrationHaptic()
        setupTts()
        startCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::tts.isInitialized) tts.shutdown()
        mediaPlayer?.release()
    }

    @Deprecated("Required for back press override")
    override fun onBackPressed() { goToVoiceMain() }

    // ── Entry animations ──────────────────────────────────────────────────────

    private fun runEntryAnimations() {
        // Tick bounces in
        val bounce = AnimationUtils.loadAnimation(this, R.anim.bounce)
        ivSuccessTick.startAnimation(bounce)

        // Glow fades in with delay
        bgGlow.alpha = 0f
        bgGlow.animate().alpha(1f).setDuration(700).setStartDelay(100).start()

        // Details card slides up
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        slideUp.startOffset = 300
        cardSuccessDetails.startAnimation(slideUp)

        // Title fades in
        tvSuccessTitle.alpha = 0f
        tvSuccessTitle.animate().alpha(1f).setDuration(500).setStartDelay(250).start()

        // Countdown pill fades in last
        tvCountdown.alpha = 0f
        tvCountdown.animate().alpha(1f).setDuration(400).setStartDelay(600).start()
    }

    // ── Sound ─────────────────────────────────────────────────────────────────

    private fun playSuccessSound() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.success_chime)
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Sound play failed", e)
        }
    }

    // ── Haptic celebration ────────────────────────────────────────────────────

    private fun celebrationHaptic() {
        vibrator?.let { v ->
            // Three short pulses — feels like a success tap
            val pattern = longArrayOf(0, 60, 80, 60, 80, 100)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyTtsLocale()
                ttsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                // Small delay so chime plays first, then TTS speaks
                handler.postDelayed({
                    tts.speak(str_success_tts, TextToSpeech.QUEUE_FLUSH, null, UTT_SUCCESS)
                }, 800)
            } else {
                Log.e(TAG, "TTS init failed")
            }
        }
    }

    private fun applyTtsLocale() {
        if (isTamil) {
            val result = tts.setLanguage(Locale("ta", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Tamil TTS unavailable — falling back to en-IN")
                tts.setLanguage(Locale("en", "IN"))
            }
        } else {
            tts.setLanguage(Locale("en", "IN"))
        }
    }

    // ── Countdown ─────────────────────────────────────────────────────────────

    private fun startCountdown() {
        updateCountdownText()
        tickCountdown()
    }

    private fun tickCountdown() {
        handler.postDelayed({
            secondsLeft--
            if (secondsLeft <= 0) goToVoiceMain()
            else { updateCountdownText(); tickCountdown() }
        }, 1000)
    }

    private fun updateCountdownText() {
        tvCountdown.text = str_countdown
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun goToVoiceMain() {
        startActivity(Intent(this, VoiceMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
}