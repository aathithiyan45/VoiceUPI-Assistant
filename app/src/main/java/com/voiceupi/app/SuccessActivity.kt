package com.voiceupi.app

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SuccessActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MERCHANT_NAME = "extra_merchant_name"
        const val EXTRA_AMOUNT        = "extra_amount"
    }

    private lateinit var tvSuccessTitle   : TextView
    private lateinit var tvPaymentDetails : TextView
    private lateinit var tvCountdown      : TextView
    private lateinit var ivSuccessTick    : ImageView

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tag     = "SuccessActivity"
    private val UTT_SUCCESS = "utt_success"
    private var secondsLeft = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_success)

        tvSuccessTitle   = findViewById(R.id.tvSuccessTitle)
        tvPaymentDetails = findViewById(R.id.tvPaymentDetails)
        tvCountdown      = findViewById(R.id.tvCountdown)
        ivSuccessTick    = findViewById(R.id.ivSuccessTick) // ✅ add this ImageView in your layout

        val merchant = intent.getStringExtra(EXTRA_MERCHANT_NAME) ?: "Merchant"
        val amount   = intent.getStringExtra(EXTRA_AMOUNT)        ?: "0"

        tvPaymentDetails.text = "₹$amount paid to $merchant"

        // ✅ Bounce animation on tick
        val bounce = AnimationUtils.loadAnimation(this, R.anim.bounce)
        ivSuccessTick.startAnimation(bounce)

        // ✅ Play success chime sound
        playSuccessSound()

        setupTts(merchant, amount)
        startCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        tts.shutdown()
        mediaPlayer?.release()
    }

    @Deprecated("Required for back press override")
    override fun onBackPressed() {
        goToVoiceMain()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Sound
    // ══════════════════════════════════════════════════════════════════════

    private fun playSuccessSound() {
        try {
            // Place a "success_chime.mp3" in res/raw/
            mediaPlayer = MediaPlayer.create(this, R.raw.success_chime)
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(tag, "Sound play failed", e)
        }
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
                // Slight delay so chime plays first
                handler.postDelayed({
                    tts.speak(
                        "Payment of ₹$amount to $merchant was successful.",
                        TextToSpeech.QUEUE_FLUSH, null, UTT_SUCCESS
                    )
                }, 800)
            } else {
                Log.e(tag, "TTS init failed")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Countdown
    // ══════════════════════════════════════════════════════════════════════

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
        tvCountdown.text = "Returning in $secondsLeft…"
    }

    private fun goToVoiceMain() {
        startActivity(Intent(this, VoiceMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
}