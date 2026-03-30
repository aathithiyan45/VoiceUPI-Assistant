package com.voiceupi.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class FakeGPayActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MERCHANT_NAME = "extra_merchant_name"
        const val EXTRA_UPI_ID        = "extra_upi_id"
        const val EXTRA_AMOUNT        = "extra_amount"
    }

    private lateinit var tvMerchantName  : TextView
    private lateinit var tvUpiId         : TextView
    private lateinit var tvAmount        : TextView
    private lateinit var tvBiometricHint : TextView
    private lateinit var ivFingerprint   : ImageView

    private var merchantName = "Merchant"
    private var upiId        = ""
    private var amount       = "0"

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_gpay)

        merchantName = intent.getStringExtra(EXTRA_MERCHANT_NAME) ?: "Merchant"
        upiId        = intent.getStringExtra(EXTRA_UPI_ID)        ?: ""
        amount       = intent.getStringExtra(EXTRA_AMOUNT)        ?: "0"

        tvMerchantName  = findViewById(R.id.tvGPayMerchant)
        tvUpiId         = findViewById(R.id.tvGPayUpiId)
        tvAmount        = findViewById(R.id.tvGPayAmount)
        tvBiometricHint = findViewById(R.id.tvBiometricHint)
        ivFingerprint   = findViewById(R.id.ivFingerprint)

        tvMerchantName.text = merchantName
        tvUpiId.text        = upiId.ifBlank { "—" }
        tvAmount.text       = "₹$amount"

        // Pulse animation on fingerprint icon
        val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
        ivFingerprint.startAnimation(pulse)

        // Auto-trigger biometric after 800ms (feels natural like real GPay)
        handler.postDelayed({ triggerBiometric() }, 800)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Biometric
    // ══════════════════════════════════════════════════════════════════════

    private fun triggerBiometric() {
        val executor: Executor = ContextCompat.getMainExecutor(this)

        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometric available — skip straight to success (demo mode)
            tvBiometricHint.text = "Authenticating…"
            handler.postDelayed({ goToSuccess() }, 1000)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Google Pay")
            .setSubtitle("Pay ₹$amount to $merchantName")
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    handler.post {
                        tvBiometricHint.text = "Authenticated ✓"
                        ivFingerprint.clearAnimation()
                        // Brief pause then go success
                        handler.postDelayed({ goToSuccess() }, 600)
                    }
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    handler.post {
                        tvBiometricHint.text = "Try again…"
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    handler.post {
                        // User cancelled → go back to VoiceMain
                        goToVoiceMain()
                    }
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Navigation
    // ══════════════════════════════════════════════════════════════════════

    private fun goToSuccess() {
        startActivity(Intent(this, SuccessActivity::class.java).apply {
            putExtra(SuccessActivity.EXTRA_MERCHANT_NAME, merchantName)
            putExtra(SuccessActivity.EXTRA_AMOUNT, amount)
        })
        finish()
    }

    private fun goToVoiceMain() {
        startActivity(Intent(this, VoiceMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}