package com.voiceupi.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * SuccessActivity — Payment confirmation result screen.
 *
 * Receives:
 *   EXTRA_MERCHANT_NAME : String
 *   EXTRA_AMOUNT        : String
 *
 * Speaks "Payment successful" then auto-returns to VoiceMainActivity after 3 s.
 */
class SuccessActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MERCHANT_NAME = "extra_merchant_name"
        const val EXTRA_AMOUNT        = "extra_amount"
    }

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var tvSuccessTitle   : TextView
    private lateinit var tvPaymentDetails : TextView
    private lateinit var tvCountdown      : TextView

    // ── TTS ────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    // ── Internal ───────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val tag     = "SuccessActivity"
    private val UTT_SUCCESS = "utt_success"

    private var secondsLeft = 3

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_success)

        tvSuccessTitle   = findViewById(R.id.tvSuccessTitle)
        tvPaymentDetails = findViewById(R.id.tvPaymentDetails)
        tvCountdown      = findViewById(R.id.tvCountdown)

        val merchant = intent.getStringExtra(EXTRA_MERCHANT_NAME) ?: "Merchant"
        val amount   = intent.getStringExtra(EXTRA_AMOUNT)        ?: "0"

        tvPaymentDetails.text = "₹$amount paid to $merchant"

        setupTts(merchant, amount)
        startCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        tts.shutdown()
    }

    // Prevent going back to confirmation
    @Deprecated("Required for back press override")
    override fun onBackPressed() {
        goToVoiceMain()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TTS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupTts(merchant: String, amount: String) {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("en", "IN")
                ttsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                val msg = "Payment of ₹$amount to $merchant was successful."
                tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, UTT_SUCCESS)
            } else {
                Log.e(tag, "TTS init failed")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Countdown → auto-navigate
    // ══════════════════════════════════════════════════════════════════════

    private fun startCountdown() {
        updateCountdownText()
        tickCountdown()
    }

    private fun tickCountdown() {
        handler.postDelayed({
            secondsLeft--
            if (secondsLeft <= 0) {
                goToVoiceMain()
            } else {
                updateCountdownText()
                tickCountdown()
            }
        }, 1000)
    }

    private fun updateCountdownText() {
        tvCountdown.text = "Returning in $secondsLeft…"
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Navigation
    // ══════════════════════════════════════════════════════════════════════

    private fun goToVoiceMain() {
        val intent = Intent(this, VoiceMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}