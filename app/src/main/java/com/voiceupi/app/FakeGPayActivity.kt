package com.voiceupi.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    // ── Locale ────────────────────────────────────────────────────────────────
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    // ── Language ──────────────────────────────────────────────────────────────
    private var isTamil = false

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvMerchantName  : TextView
    private lateinit var tvUpiId         : TextView
    private lateinit var tvAmount        : TextView
    private lateinit var tvBiometricHint : TextView
    private lateinit var ivFingerprint   : ImageView

    // ── Data ──────────────────────────────────────────────────────────────────
    private var merchantName = "Merchant"
    private var upiId        = ""
    private var amount       = "0"

    private val handler = Handler(Looper.getMainLooper())

    // ── Localised strings ─────────────────────────────────────────────────────

    private val str_biometric_title    get() = "Google Pay"
    private val str_biometric_subtitle get() = if (isTamil)
        "$merchantName-க்கு ₹$amount செலுத்தவும்"
    else "Pay ₹$amount to $merchantName"

    private val str_authenticating get() = if (isTamil) "அங்கீகரிக்கிறோம்…"        else "Authenticating…"
    private val str_authenticated  get() = if (isTamil) "அங்கீகரிக்கப்பட்டது ✓"    else "Authenticated ✓"
    private val str_try_again      get() = if (isTamil) "மீண்டும் முயற்சிக்கவும்…" else "Try again…"
    private val str_hint_touch     get() = if (isTamil)
        "விரல் ரேகை வைக்கவும் அல்லது PIN உள்ளிடவும்"
    else "Touch fingerprint or enter PIN"

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_gpay)

        isTamil      = intent.getBooleanExtra("IS_TAMIL", false)
        merchantName = intent.getStringExtra(EXTRA_MERCHANT_NAME) ?: "Merchant"
        upiId        = intent.getStringExtra(EXTRA_UPI_ID)        ?: ""
        amount       = intent.getStringExtra(EXTRA_AMOUNT)        ?: "0"

        tvMerchantName  = findViewById(R.id.tvGPayMerchant)
        tvUpiId         = findViewById(R.id.tvGPayUpiId)
        tvAmount        = findViewById(R.id.tvGPayAmount)
        tvBiometricHint = findViewById(R.id.tvBiometricHint)
        ivFingerprint   = findViewById(R.id.ivFingerprint)

        tvMerchantName.text  = merchantName
        tvUpiId.text         = upiId.ifBlank { "—" }
        tvAmount.text        = "₹$amount"
        tvBiometricHint.text = str_hint_touch

        val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
        ivFingerprint.startAnimation(pulse)

        handler.postDelayed({ triggerBiometric() }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Biometric ─────────────────────────────────────────────────────────────

    private fun triggerBiometric() {
        val executor: Executor = ContextCompat.getMainExecutor(this)

        val canAuth = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            tvBiometricHint.text = str_authenticating
            handler.postDelayed({ goToSuccess() }, 1000)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(str_biometric_title)
            .setSubtitle(str_biometric_subtitle)
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                handler.post {
                    tvBiometricHint.text = str_authenticated
                    ivFingerprint.clearAnimation()
                    handler.postDelayed({ goToSuccess() }, 600)
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                handler.post { tvBiometricHint.text = str_try_again }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                handler.post { goToVoiceMain() }
            }

        }).authenticate(promptInfo)
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun goToSuccess() {
        startActivity(Intent(this, SuccessActivity::class.java).apply {
            putExtra(SuccessActivity.EXTRA_MERCHANT_NAME, merchantName)
            putExtra(SuccessActivity.EXTRA_AMOUNT, amount)
            putExtra("IS_TAMIL", isTamil)
        })
        finish()
    }

    private fun goToVoiceMain() {
        startActivity(Intent(this, VoiceMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
}