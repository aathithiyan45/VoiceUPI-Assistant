package com.voiceupi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.Locale

// ══════════════════════════════════════════════════════════════════════════════
//  Constants
// ══════════════════════════════════════════════════════════════════════════════

private const val TAG = "VoiceUPI"

private const val REQ_AMOUNT        = 100
private const val REQ_UPI_ID        = 300
private const val REQ_CONTACT_NAME  = 400

private const val MAX_RETRY = 2

private const val UPI_LITE_MAX_AMOUNT     = 100000.0
private const val UPI_LITE_WALLET_BALANCE = 100000.0

private const val UTT_WELCOME        = "utt_welcome"
private const val UTT_ALIGN_GUIDE    = "utt_align_guide"
private const val UTT_QR_DETECTED    = "utt_qr_detected"
private const val UTT_AMOUNT_PROMPT  = "utt_amount_prompt"
private const val UTT_UPIID_PROMPT   = "utt_upiid_prompt"
private const val UTT_CONTACT_PROMPT = "utt_contact_prompt"
private const val UTT_SUCCESS        = "utt_success"
private const val UTT_CANCEL         = "utt_cancel"
private const val UTT_ERROR          = "utt_error"
private const val UTT_LIMIT_WARN     = "utt_limit_warn"
private const val UTT_FRAUD_WARN     = "utt_fraud_warn"
private const val UTT_BALANCE_LOW    = "utt_balance_low"
private const val UTT_LANG_SWITCH    = "utt_lang_switch"

// ══════════════════════════════════════════════════════════════════════════════
//  State machine
// ══════════════════════════════════════════════════════════════════════════════

private enum class PaymentState {
    IDLE,
    ALIGNING,
    QR_DETECTED,
    AMOUNT_NEEDED,
    UPIID_INPUT,
    CONTACT_INPUT,
    CONFIRMING,
    FRAUD_CHECK,
    PROCESSING,
    DONE
}

// ══════════════════════════════════════════════════════════════════════════════
//  Multilingual support
// ══════════════════════════════════════════════════════════════════════════════

internal enum class AppLanguage(val locale: Locale, val label: String) {
    ENGLISH(Locale("en", "IN"), "English"),
    HINDI(Locale("hi", "IN"), "Hindi"),
    TAMIL(Locale("ta", "IN"), "Tamil")
}

// ══════════════════════════════════════════════════════════════════════════════
//  Data
// ══════════════════════════════════════════════════════════════════════════════

private data class UpiPayload(
    val payeeVpa: String?,
    val payeeName: String?,
    val amount: String?,
    val merchantCode: String?,
    val transactionRef: String?
)

// ══════════════════════════════════════════════════════════════════════════════
//  Activity
// ══════════════════════════════════════════════════════════════════════════════

@SuppressLint("SetTextI18n")
class QRScannerActivity : ComponentActivity() {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scanOverlay: ScanOverlayView

    // ── TTS ────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var currentLanguage = AppLanguage.ENGLISH

    // ── State ──────────────────────────────────────────────────────────────
    private var state: PaymentState = PaymentState.IDLE
    private var payload: UpiPayload? = null
    private var currentAmount: String? = null
    private var isScanned = false
    private var retryCount = 0
    private var simulatedBalance = UPI_LITE_WALLET_BALANCE

    // ── Camera ─────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null

    // ── Threading & haptic ─────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null

    // ── Alignment guidance ─────────────────────────────────────────────────
    private var alignmentGuidanceCount = 0
    private val alignmentRunnable = object : Runnable {
        override fun run() {
            if (state == PaymentState.IDLE && !isScanned) {
                if (alignmentGuidanceCount % 3 == 0) speakAlignmentGuidance()
                alignmentGuidanceCount++
                handler.postDelayed(this, 5000)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        buildUI()
        initTTS()
        if (hasCamera()) startCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (state == PaymentState.IDLE) {
            alignmentGuidanceCount = 0
            handler.postDelayed(alignmentRunnable, 6000)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(alignmentRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════════════════

    private fun buildUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        scanOverlay = ScanOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).also { lp -> lp.setMargins(0, 0, 0, 160) }
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            setBackgroundColor(0xCC000000.toInt())
            text = "📷 Point camera at UPI QR code"
        }

        subText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).also { lp -> lp.setMargins(0, 0, 0, 80) }
            textSize = 13f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.CENTER
            setPadding(40, 8, 40, 8)
            setBackgroundColor(0xAA000000.toInt())
            text = "Tap anywhere to enter UPI ID manually"
        }

        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.GONE
        }

        root.addView(previewView)
        root.addView(scanOverlay)
        root.addView(statusText)
        root.addView(subText)
        root.addView(progressBar)
        setContentView(root)

        root.setOnClickListener {
            if (state == PaymentState.IDLE) offerAlternativeInput()
        }
    }

    private fun setStatus(primary: String, secondary: String = "") {
        runOnUiThread {
            statusText.text = primary
            subText.text = secondary
            subText.visibility = if (secondary.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showSpinner(show: Boolean) {
        runOnUiThread { progressBar.visibility = if (show) View.VISIBLE else View.GONE }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Permissions
    // ══════════════════════════════════════════════════════════════════════

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                setStatus("❌ Camera permission denied", "Go to Settings → Apps → VoiceUPI → Permissions")
                speak("Camera permission is required to scan QR codes.", UTT_ERROR)
            }
        }

    // ══════════════════════════════════════════════════════════════════════
    //  TTS
    // ══════════════════════════════════════════════════════════════════════

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) { Log.e(TAG, "TTS init failed"); return@TextToSpeech }
            applyLanguage(currentLanguage)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { Log.e(TAG, "TTS error: $id") }
                override fun onDone(id: String?) {
                    handler.post {
                        when (id) {
                            UTT_WELCOME        -> { /* camera handles next step */ }
                            UTT_ALIGN_GUIDE    -> { /* periodic; no action */ }
                            UTT_QR_DETECTED    -> proceedAfterQRAnnouncement()
                            UTT_AMOUNT_PROMPT,
                            UTT_LIMIT_WARN     -> launchVoiceInput(REQ_AMOUNT)
                            UTT_UPIID_PROMPT   -> launchVoiceInput(REQ_UPI_ID)
                            UTT_CONTACT_PROMPT -> launchVoiceInput(REQ_CONTACT_NAME)
                            UTT_FRAUD_WARN,
                            UTT_ERROR,
                            UTT_BALANCE_LOW    -> handler.postDelayed({ resetForNextScan() }, 1500)
                            UTT_SUCCESS,
                            UTT_CANCEL         -> handler.postDelayed({ resetForNextScan() }, 2500)
                        }
                    }
                }
            })

            speak(
                "Welcome to Voice UPI. Point your camera at a UPI QR code to begin. " +
                        "You can also tap the screen to enter a UPI ID manually.",
                UTT_WELCOME
            )
        }
    }

    private fun applyLanguage(lang: AppLanguage) {
        val result = tts.setLanguage(lang.locale)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) {
            tts.setLanguage(Locale.ENGLISH)
            ttsReady = true
            Log.w(TAG, "${lang.label} TTS unavailable, falling back to English")
        }
    }

    private fun speak(text: String, utteranceId: String) {
        Log.d(TAG, "TTS[$utteranceId]: $text")
        if (!::tts.isInitialized || !ttsReady) {
            handler.postDelayed({
                when (utteranceId) {
                    UTT_AMOUNT_PROMPT,
                    UTT_LIMIT_WARN     -> launchVoiceInput(REQ_AMOUNT)
                    UTT_UPIID_PROMPT   -> launchVoiceInput(REQ_UPI_ID)
                    UTT_CONTACT_PROMPT -> launchVoiceInput(REQ_CONTACT_NAME)
                    UTT_SUCCESS, UTT_CANCEL,
                    UTT_ERROR, UTT_FRAUD_WARN,
                    UTT_BALANCE_LOW    -> handler.postDelayed({ resetForNextScan() }, 1500)
                }
            }, 300)
            return
        }
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun speakAlignmentGuidance() {
        if (state != PaymentState.IDLE) return
        val tips = listOf(
            "Hold your phone steady and point the camera at the QR code.",
            "Move the camera slowly over the QR code. Keep it about 20 centimeters away.",
            "Ensure the QR code is well-lit. Move to a brighter area if needed.",
            "Try tilting your phone slightly for a better angle."
        )
        val tip = tips[alignmentGuidanceCount % tips.size]
        setStatus("📷 Scanning…", tip)
        hapticPulse(longArrayOf(0, 30))
        speak(tip, UTT_ALIGN_GUIDE)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Camera
    // ══════════════════════════════════════════════════════════════════════

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val barcodeScanner = BarcodeScanning.getClient()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                    analyzeFrame(barcodeScanner, proxy)
                }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
                handler.postDelayed(alignmentRunnable, 6000)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                setStatus("❌ Camera error", e.localizedMessage ?: "")
                speak("Camera failed to start. Please restart the app.", UTT_ERROR)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        proxy: ImageProxy
    ) {
        if (isScanned || state != PaymentState.IDLE) { proxy.close(); return }
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val raw = barcode.rawValue ?: continue
                    if (isScanned) break
                    isScanned = true
                    handler.removeCallbacks(alignmentRunnable)
                    hapticPulse(longArrayOf(0, 80, 60, 80))
                    scanOverlay.showDetected(true)
                    handleScannedQR(raw)
                    break
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Scan fail", e) }
            .addOnCompleteListener { proxy.close() }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  QR handling
    // ══════════════════════════════════════════════════════════════════════

    private fun handleScannedQR(raw: String) {
        Log.d(TAG, "QR raw: $raw")
        if (!raw.startsWith("upi://")) {
            setStatus("⚠️ Not a UPI QR code", "Please scan a UPI payment QR")
            speak("This is not a UPI QR code. Please try again with a UPI QR.", UTT_ERROR)
            isScanned = false
            return
        }

        val p = parseUPI(raw)
        payload = p

        // ✅ FIX: Log full payload so we can debug missing pa= field
        Log.d(TAG, "Parsed payload → pa=${p.payeeVpa} pn=${p.payeeName} am=${p.amount}")

        if (p.payeeVpa.isNullOrBlank()) {
            Log.w(TAG, "⚠️ payeeVpa is null/blank! Full QR: $raw")
            setStatus("⚠️ Invalid QR", "UPI ID (pa=) missing in this QR code")
            speak("This QR code does not contain a valid UPI ID. Please try another QR code.", UTT_ERROR)
            isScanned = false
            return
        }

        state = PaymentState.QR_DETECTED

        if (!isMerchantValid(p)) {
            state = PaymentState.FRAUD_CHECK
            setStatus("🚨 Suspicious QR detected", "Merchant validation failed — payment blocked")
            hapticPulse(longArrayOf(0, 200, 100, 200, 100, 200))
            speak(
                "Warning! This QR code appears suspicious. " +
                        "The merchant UPI ID is missing or invalid. Payment has been blocked for your safety.",
                UTT_FRAUD_WARN
            )
            return
        }

        val name = p.payeeName?.takeIf { it.isNotBlank() } ?: "Unknown Merchant"
        val vpa  = p.payeeVpa

        val announcement = buildString {
            append("QR code detected. Merchant: $name. ")
            append("UPI ID: $vpa. ")
            if (!p.amount.isNullOrBlank()) {
                append("Amount: ${formatAmount(p.amount)} rupees. ")
                currentAmount = p.amount
            }
        }

        setStatus("✅ $name", "UPI: $vpa${if (!p.amount.isNullOrBlank()) " | ₹${p.amount}" else ""}")
        speak(announcement, UTT_QR_DETECTED)
    }

    private fun proceedAfterQRAnnouncement() {
        if (currentAmount != null) {
            proceedToConfirmation()
        } else {
            state = PaymentState.AMOUNT_NEEDED
            val name = payload?.payeeName ?: "this merchant"
            speak("Please say the amount you want to pay to $name.", UTT_AMOUNT_PROMPT)
            setStatus("🎤 Speak the amount", "Say a number, e.g. \"fifty\" or \"100\"")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Alternative input (no QR)
    // ══════════════════════════════════════════════════════════════════════

    private fun offerAlternativeInput() {
        state = PaymentState.UPIID_INPUT
        speak(
            "You can pay without a QR code. Say a UPI ID, for example john at ok hdfc bank. " +
                    "Or say a contact name.",
            UTT_UPIID_PROMPT
        )
        setStatus("🎤 Say UPI ID or contact name", "e.g. \"john at okhdfcbank\"")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Proceed to ConfirmationActivity
    // ══════════════════════════════════════════════════════════════════════

    private fun proceedToConfirmation() {
        val amount = currentAmount?.toDoubleOrNull()

        // Balance check
        if (amount != null && amount > simulatedBalance) {
            setStatus("⚠️ Insufficient UPI Lite balance", "Balance: ₹${simulatedBalance.toLong()}")
            speak(
                "Insufficient balance. Your UPI Lite wallet has only ${simulatedBalance.toLong()} rupees. " +
                        "Please top up and try again.",
                UTT_BALANCE_LOW
            )
            return
        }

        // Limit check
        if (amount != null && amount > UPI_LITE_MAX_AMOUNT) {
            setStatus("⚠️ Exceeds UPI Lite limit", "Maximum is ₹500 per transaction")
            speak(
                "The amount ${formatAmount(currentAmount!!)} rupees exceeds the UPI Lite limit of 500 rupees. " +
                        "Please say a lower amount.",
                UTT_LIMIT_WARN
            )
            state = PaymentState.AMOUNT_NEEDED
            currentAmount = null
            return
        }

        // ✅ FIX: Safely extract upiId with detailed log if missing
        val upiId = payload?.payeeVpa?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "⚠️ payeeVpa is null/blank at confirmation! payload=$payload")
            ""
        }

        val name = payload?.payeeName?.takeIf { it.isNotBlank() } ?: "Merchant"
        val amt  = formatAmount(currentAmount ?: "0")

        Log.d(TAG, "→ ConfirmationActivity: name=$name upiId=$upiId amt=$amt")

        state = PaymentState.DONE
        setStatus("✅ Opening confirmation…", "₹$amt → $name")
        speak("Opening confirmation screen.", UTT_QR_DETECTED)

        handler.postDelayed({
            val intent = Intent(this, ConfirmationActivity::class.java).apply {
                putExtra(ConfirmationActivity.EXTRA_MERCHANT_NAME, name)
                putExtra(ConfirmationActivity.EXTRA_UPI_ID, upiId)
                putExtra(ConfirmationActivity.EXTRA_AMOUNT, amt)
            }
            startActivity(intent)
            finish()
        }, 1000)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Voice input
    // ══════════════════════════════════════════════════════════════════════

    private fun launchVoiceInput(requestCode: Int) {
        val prompt = when (requestCode) {
            REQ_AMOUNT       -> "Say the amount in rupees"
            REQ_UPI_ID       -> "Say the UPI ID or contact name"
            REQ_CONTACT_NAME -> "Say the contact name"
            else             -> "Speak now"
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage.locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "Speech recognizer unavailable", e)
            setStatus("❌ Microphone unavailable", "Speech recognition not found on this device")
            speak("Voice input is not available on this device.", UTT_ERROR)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Activity result
    // ══════════════════════════════════════════════════════════════════════

    @Deprecated("Required for RecognizerIntent compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK || data == null) {
            handleVoiceError(requestCode, "cancelled or no data")
            return
        }
        val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (results.isNullOrEmpty()) {
            handleVoiceError(requestCode, "empty results")
            return
        }
        Log.d(TAG, "Voice[$requestCode] candidates: $results")

        when (requestCode) {
            REQ_AMOUNT       -> onAmountVoice(results)
            REQ_UPI_ID       -> onUpiIdVoice(results)
            REQ_CONTACT_NAME -> onContactVoice(results)
        }
    }

    // ── Amount ─────────────────────────────────────────────────────────────

    private fun onAmountVoice(candidates: List<String>) {
        val amount = candidates
            .mapNotNull { extractAmount(it) }
            .firstOrNull { it.toDoubleOrNull()?.let { d -> d > 0 } == true }

        if (amount == null) {
            retryCount++
            return if (retryCount <= MAX_RETRY) {
                speak(
                    "I could not understand the amount. Please say only the number, for example, fifty.",
                    UTT_AMOUNT_PROMPT
                )
                setStatus("🔁 Didn't catch that", "Say the amount again")
            } else {
                retryCount = 0
                speak("Too many failed attempts. Please scan the QR code again.", UTT_ERROR)
                setStatus("❌ Voice input failed", "Please scan again")
            }
        }

        retryCount = 0
        currentAmount = amount
        setStatus("✅ Amount: ₹$amount", "Checking limits…")
        proceedToConfirmation()
    }

    // ── UPI ID ──────────────────────────────────────────────────────────────

    private fun onUpiIdVoice(candidates: List<String>) {
        val raw = candidates[0].lowercase().trim()
        val vpa = reconstructUpiId(raw)

        if (vpa == null) {
            retryCount++
            return if (retryCount <= MAX_RETRY) {
                speak(
                    "Could not understand the UPI ID. Please say it clearly, for example, john at okhdfcbank.",
                    UTT_UPIID_PROMPT
                )
            } else {
                retryCount = 0
                speak("UPI ID input failed. Please try scanning a QR code instead.", UTT_ERROR)
                resetForNextScan()
            }
        }

        retryCount = 0
        val p = UpiPayload(
            payeeVpa = vpa, payeeName = vpa,
            amount = null, merchantCode = null, transactionRef = null
        )
        payload = p

        if (!isMerchantValid(p)) {
            speak("The UPI ID $vpa appears invalid. Please try again.", UTT_FRAUD_WARN)
            return
        }

        state = PaymentState.AMOUNT_NEEDED
        speak("UPI ID set to $vpa. Please say the amount.", UTT_AMOUNT_PROMPT)
        setStatus("✅ UPI: $vpa", "Now say the amount")
    }

    // ── Contact ─────────────────────────────────────────────────────────────

    private fun onContactVoice(candidates: List<String>) {
        val name = candidates[0].trim().replaceFirstChar { it.uppercase() }
        val resolvedVpa = resolveContactToVpa(name)
        if (resolvedVpa == null) {
            speak("Could not find a UPI ID for $name. Please try again or scan a QR code.", UTT_ERROR)
            resetForNextScan()
            return
        }
        payload = UpiPayload(
            payeeVpa = resolvedVpa, payeeName = name,
            amount = null, merchantCode = null, transactionRef = null
        )
        state = PaymentState.AMOUNT_NEEDED
        speak("Paying $name at $resolvedVpa. Please say the amount.", UTT_AMOUNT_PROMPT)
        setStatus("✅ Contact: $name", "Now say the amount")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Fraud / merchant validation
    // ══════════════════════════════════════════════════════════════════════

    private fun isMerchantValid(p: UpiPayload): Boolean {
        val vpa = p.payeeVpa ?: return false
        val vpaRegex = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z]{2,}$")
        if (!vpaRegex.matches(vpa)) return false
        p.amount?.let { amt ->
            if (amt.toDoubleOrNull()?.let { it <= 0 } == true) return false
        }
        val blocklist = listOf("test@", "fake@", "dummy@", "scam@")
        if (blocklist.any { vpa.lowercase().startsWith(it) }) return false
        return true
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun parseUPI(raw: String): UpiPayload {
        return try {
            val uri = Uri.parse(raw)
            UpiPayload(
                payeeVpa       = uri.getQueryParameter("pa"),
                payeeName      = uri.getQueryParameter("pn"),
                amount         = uri.getQueryParameter("am"),
                merchantCode   = uri.getQueryParameter("mc"),
                transactionRef = uri.getQueryParameter("tr")
            )
        } catch (e: Exception) {
            Log.e(TAG, "UPI parse error", e)
            UpiPayload(null, null, null, null, null)
        }
    }

    private fun extractAmount(text: String): String? {
        val wordMap = mapOf(
            "five hundred" to "500", "four hundred" to "400", "three hundred" to "300",
            "two hundred" to "200", "one hundred" to "100", "hundred" to "100",
            "ninety" to "90", "eighty" to "80", "seventy" to "70", "sixty" to "60",
            "fifty" to "50", "forty" to "40", "thirty" to "30", "twenty" to "20",
            "nineteen" to "19", "eighteen" to "18", "seventeen" to "17", "sixteen" to "16",
            "fifteen" to "15", "fourteen" to "14", "thirteen" to "13", "twelve" to "12",
            "eleven" to "11", "ten" to "10", "nine" to "9", "eight" to "8",
            "seven" to "7", "six" to "6", "five" to "5", "four" to "4",
            "three" to "3", "two" to "2", "one" to "1", "zero" to "0"
        )
        val lower = text.lowercase().trim()
        wordMap.entries.sortedByDescending { it.key.length }
            .forEach { (word, num) -> if (lower.contains(word)) return num }
        val regex = Regex("""(\d+(?:\.\d{1,2})?)""")
        return regex.find(text)?.value
    }

    private fun formatAmount(amount: String): String {
        val d = amount.toDoubleOrNull() ?: return amount
        return if (d == kotlin.math.floor(d)) d.toLong().toString() else "%.2f".format(d)
    }

    private fun reconstructUpiId(spoken: String): String? {
        val normalized = spoken
            .replace(" at the rate of ", "@")
            .replace(" at the rate ", "@")
            .replace(" at ", "@")
            .replace(" ", "")
        val vpaRegex = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z]{2,}$")
        return if (vpaRegex.matches(normalized)) normalized else null
    }

    private fun resolveContactToVpa(name: String): String? {
        val stubContacts = mapOf(
            "Amma"  to "amma@okaxis",
            "Appa"  to "appa@okhdfcbank",
            "Ravi"  to "ravi.k@ybl",
            "Priya" to "priya99@paytm"
        )
        return stubContacts[name]
    }

    private fun handleVoiceError(requestCode: Int, reason: String) {
        Log.w(TAG, "Voice error req=$requestCode: $reason")
        retryCount++
        if (retryCount <= MAX_RETRY) {
            val uttId = when (requestCode) {
                REQ_AMOUNT       -> UTT_AMOUNT_PROMPT
                REQ_UPI_ID       -> UTT_UPIID_PROMPT
                REQ_CONTACT_NAME -> UTT_CONTACT_PROMPT
                else             -> UTT_ERROR
            }
            speak("Sorry, I didn't catch that. Please try again.", uttId)
            setStatus("🔁 Try again", "")
        } else {
            retryCount = 0
            speak("Voice input failed. Please scan the QR code again.", UTT_ERROR)
            setStatus("❌ Input failed", "Please scan again")
        }
    }

    private fun resetForNextScan() {
        isScanned              = false
        payload                = null
        currentAmount          = null
        retryCount             = 0
        state                  = PaymentState.IDLE
        alignmentGuidanceCount = 0
        scanOverlay.showDetected(false)
        setStatus("📷 Point camera at UPI QR code", "Tap anywhere to enter UPI ID manually")
        handler.postDelayed(alignmentRunnable, 6000)
        Log.d(TAG, "Reset complete")
    }

    internal fun switchLanguage(lang: AppLanguage) {
        currentLanguage = lang
        applyLanguage(lang)
        speak("Language changed to ${lang.label}.", UTT_LANG_SWITCH)
    }

    private fun hapticPulse(pattern: LongArray) {
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