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

private const val REQ_AMOUNT       = 100
private const val REQ_UPI_ID       = 300
private const val REQ_CONTACT_NAME = 400

private const val MAX_RETRY = 2

private const val UPI_LITE_MAX_AMOUNT     = 500.0
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

// ══════════════════════════════════════════════════════════════════════════════
//  State machine
// ══════════════════════════════════════════════════════════════════════════════

private enum class PaymentState {
    IDLE, ALIGNING, QR_DETECTED, AMOUNT_NEEDED,
    UPIID_INPUT, CONTACT_INPUT, CONFIRMING,
    FRAUD_CHECK, PROCESSING, DONE
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

    // ── Language ───────────────────────────────────────────────────────────
    private var isTamil = false

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scanOverlay: ScanOverlayView

    // ── TTS ────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

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
    //  Localised strings
    // ══════════════════════════════════════════════════════════════════════

    private val str_welcome get() = if (isTamil)
        "Voice UPI-க்கு வரவேற்கிறோம். QR கோடை கேமராவில் காட்டுங்கள். " +
                "திரையை தட்டி UPI ID கொடுக்கலாம்."
    else
        "Welcome to Voice UPI. Point your camera at a UPI QR code. " +
                "Tap the screen to enter a UPI ID manually."

    private val str_scan_prompt get() = if (isTamil)
        "📷 QR கோடை காட்டுங்கள்"
    else "📷 Point camera at UPI QR code"

    private val str_tap_manual get() = if (isTamil)
        "திரையை தட்டி UPI ID கொடுக்கலாம்"
    else "Tap anywhere to enter UPI ID manually"

    private val str_not_upi get() = if (isTamil)
        "⚠️ இது UPI QR இல்லை. மீண்டும் முயற்சிக்கவும்."
    else "⚠️ Not a UPI QR code. Please try again."

    private val str_not_upi_tts get() = if (isTamil)
        "இது UPI QR கோடு இல்லை. வேறு QR கோடு காட்டுங்கள்."
    else "This is not a UPI QR code. Please try again with a UPI QR."

    private val str_invalid_qr get() = if (isTamil)
        "⚠️ QR சரியில்லை. UPI ID இல்லை."
    else "⚠️ Invalid QR. UPI ID missing."

    private val str_invalid_qr_tts get() = if (isTamil)
        "இந்த QR-ல் சரியான UPI ID இல்லை. வேறு QR காட்டுங்கள்."
    else "This QR code does not contain a valid UPI ID. Please try another."

    private val str_fraud_status get() = if (isTamil)
        "🚨 சந்தேகமான QR. பணம் தடுக்கப்பட்டது."
    else "🚨 Suspicious QR detected. Payment blocked."

    private val str_fraud_tts get() = if (isTamil)
        "எச்சரிக்கை! இந்த QR சந்தேகமானது. பாதுகாப்பிற்காக பணம் தடுக்கப்பட்டது."
    else "Warning! This QR code appears suspicious. Payment has been blocked for your safety."

    private val str_camera_denied get() = if (isTamil)
        "❌ கேமரா அனுமதி இல்லை. Settings-ல் அனுமதிக்கவும்."
    else "❌ Camera permission denied. Go to Settings → Apps → VoiceUPI → Permissions"

    private val str_camera_denied_tts get() = if (isTamil)
        "கேமரா அனுமதி தேவை. Settings-ல் அனுமதிக்கவும்."
    else "Camera permission is required to scan QR codes."

    private val str_camera_error_tts get() = if (isTamil)
        "கேமரா தொடங்கவில்லை. App மீண்டும் திறக்கவும்."
    else "Camera failed to start. Please restart the app."

    private val str_opening_confirm get() = if (isTamil)
        "உறுதிப்படுத்தல் திரை திறக்கிறது."
    else "Opening confirmation screen."

    private val str_balance_low_tts get() = if (isTamil)
        "பணம் போதுமான அளவு இல்லை. உங்கள் UPI Lite-ல் ${simulatedBalance.toLong()} ரூபாய் மட்டுமே உள்ளது."
    else "Insufficient balance. Your UPI Lite wallet has only ${simulatedBalance.toLong()} rupees. Please top up."

    private val str_balance_low_status get() = if (isTamil)
        "⚠️ பணம் போதாது. இருப்பு: ₹${simulatedBalance.toLong()}"
    else "⚠️ Insufficient UPI Lite balance. Balance: ₹${simulatedBalance.toLong()}"

    private val str_limit_warn_tts get() = if (isTamil)
        "தொகை UPI Lite வரம்பை மீறியது. அதிகபட்சம் 500 ரூபாய் மட்டுமே. சரியான தொகை சொல்லுங்கள்."
    else "The amount exceeds the UPI Lite limit of 500 rupees. Please say a lower amount."

    private val str_limit_warn_status get() = if (isTamil)
        "⚠️ UPI Lite வரம்பு தாண்டியது. அதிகபட்சம் ₹500"
    else "⚠️ Exceeds UPI Lite limit. Maximum is ₹500"

    private val str_amount_prompt_tts get() = if (isTamil)
        "நீங்கள் எவ்வளவு ரூபாய் அனுப்ப வேண்டும்? தொகை சொல்லுங்கள்."
    else "Please say the amount you want to pay."

    private val str_amount_prompt_status get() = if (isTamil)
        "🎤 தொகை சொல்லுங்கள்"
    else "🎤 Speak the amount"

    private val str_amount_sub get() = if (isTamil)
        "உதா: ஐம்பது, நூறு"
    else "Say a number, e.g. \"fifty\" or \"100\""

    private val str_upiid_prompt_tts get() = if (isTamil)
        "QR இல்லாமல் பணம் அனுப்பலாம். UPI ID சொல்லுங்கள். உதாரணம்: john at okhdfcbank."
    else "You can pay without a QR code. Say a UPI ID, for example john at ok hdfc bank."

    private val str_upiid_status get() = if (isTamil)
        "🎤 UPI ID சொல்லுங்கள்"
    else "🎤 Say UPI ID or contact name"

    private val str_amount_retry_tts get() = if (isTamil)
        "தொகை புரியவில்லை. மட்டும் எண் சொல்லுங்கள். உதா: ஐம்பது."
    else "I could not understand the amount. Please say only the number, for example fifty."

    private val str_amount_failed_tts get() = if (isTamil)
        "தொகை கேட்கவில்லை. மீண்டும் QR ஸ்கேன் செய்யவும்."
    else "Too many failed attempts. Please scan the QR code again."

    private val str_upiid_retry_tts get() = if (isTamil)
        "UPI ID புரியவில்லை. தெளிவாக சொல்லுங்கள். உதா: john at okhdfcbank."
    else "Could not understand the UPI ID. Please say it clearly, for example john at okhdfcbank."

    private val str_upiid_failed_tts get() = if (isTamil)
        "UPI ID கேட்கவில்லை. QR ஸ்கேன் முயற்சிக்கவும்."
    else "UPI ID input failed. Please try scanning a QR code instead."

    private val str_no_speech_tts get() = if (isTamil)
        "மீண்டும் முயற்சிக்கவும்."
    else "Sorry, I didn't catch that. Please try again."

    private val str_mic_unavailable_tts get() = if (isTamil)
        "மைக்ரோஃபோன் கிடைக்கவில்லை."
    else "Voice input is not available on this device."

    // ✅ FIX 1: ta-IN for Tamil so engine transcribes native Tamil script;
    //           en-IN for English mode.
    private val asrLocale get() = if (isTamil) "ta-IN" else "en-IN"

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTamil = intent.getBooleanExtra("IS_TAMIL", false)
        Log.d(TAG, "QRScannerActivity isTamil=$isTamil")

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
            ).also { it.setMargins(0, 0, 0, 160) }
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            setBackgroundColor(0xCC000000.toInt())
            text = str_scan_prompt
        }

        subText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).also { it.setMargins(0, 0, 0, 80) }
            textSize = 13f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.CENTER
            setPadding(40, 8, 40, 8)
            setBackgroundColor(0xAA000000.toInt())
            text = str_tap_manual
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
                setStatus(str_camera_denied)
                speak(str_camera_denied_tts, UTT_ERROR)
            }
        }

    // ══════════════════════════════════════════════════════════════════════
    //  TTS
    // ══════════════════════════════════════════════════════════════════════

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed")
                return@TextToSpeech
            }
            applyTtsLocale()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { Log.e(TAG, "TTS error: $id") }
                override fun onDone(id: String?) {
                    handler.post {
                        when (id) {
                            UTT_QR_DETECTED              -> proceedAfterQRAnnouncement()
                            UTT_AMOUNT_PROMPT,
                            UTT_LIMIT_WARN               -> launchVoiceInput(REQ_AMOUNT)
                            UTT_UPIID_PROMPT             -> launchVoiceInput(REQ_UPI_ID)
                            UTT_CONTACT_PROMPT           -> launchVoiceInput(REQ_CONTACT_NAME)
                            UTT_FRAUD_WARN,
                            UTT_ERROR,
                            UTT_BALANCE_LOW              -> handler.postDelayed({ resetForNextScan() }, 1500)
                            UTT_SUCCESS, UTT_CANCEL      -> handler.postDelayed({ resetForNextScan() }, 2500)
                        }
                    }
                }
            })
            speak(str_welcome, UTT_WELCOME)
        }
    }

    private fun applyTtsLocale() {
        if (isTamil) {
            val result = tts.setLanguage(Locale("ta", "IN"))
            val tamilOk = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!tamilOk) {
                Log.w(TAG, "Tamil TTS missing — falling back to en-IN")
                tts.setLanguage(Locale("en", "IN"))
            }
        } else {
            tts.setLanguage(Locale("en", "IN"))
        }
        ttsReady = true
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
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun speakAlignmentGuidance() {
        if (state != PaymentState.IDLE) return
        val tips = if (isTamil) listOf(
            "கேமராவை நேராக QR-ல் வையுங்கள். சுமார் 20 செ.மீ. தூரத்தில் வையுங்கள்.",
            "மெதுவாக நகர்த்துங்கள். நல்ல வெளிச்சம் இருக்கட்டும்.",
            "கொஞ்சம் சாய்த்துப் பாருங்கள். QR தெளிவாக தெரிய வேண்டும்.",
            "கேமராவை நிலையாக வையுங்கள்."
        ) else listOf(
            "Hold your phone steady and point the camera at the QR code.",
            "Move the camera slowly. Keep it about 20 centimeters away.",
            "Ensure the QR code is well-lit. Move to a brighter area if needed.",
            "Try tilting your phone slightly for a better angle."
        )
        val tip = tips[alignmentGuidanceCount % tips.size]
        setStatus("📷 ${if (isTamil) "ஸ்கேன் செய்கிறேன்…" else "Scanning…"}", tip)
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
                setStatus("❌ ${if (isTamil) "கேமரா பிழை" else "Camera error"}", e.localizedMessage ?: "")
                speak(str_camera_error_tts, UTT_ERROR)
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
            setStatus(str_not_upi)
            speak(str_not_upi_tts, UTT_ERROR)
            isScanned = false
            return
        }

        val p = parseUPI(raw)
        payload = p
        Log.d(TAG, "Parsed → pa=${p.payeeVpa} pn=${p.payeeName} am=${p.amount}")

        if (p.payeeVpa.isNullOrBlank()) {
            Log.w(TAG, "payeeVpa null/blank! Full QR: $raw")
            setStatus(str_invalid_qr)
            speak(str_invalid_qr_tts, UTT_ERROR)
            isScanned = false
            return
        }

        state = PaymentState.QR_DETECTED

        if (!isMerchantValid(p)) {
            state = PaymentState.FRAUD_CHECK
            setStatus(str_fraud_status)
            hapticPulse(longArrayOf(0, 200, 100, 200, 100, 200))
            speak(str_fraud_tts, UTT_FRAUD_WARN)
            return
        }

        val name = p.payeeName?.takeIf { it.isNotBlank() }
            ?: if (isTamil) "வணிகர்" else "Unknown Merchant"
        val vpa  = p.payeeVpa

        val announcement = if (isTamil)
            "QR கோடு கண்டறியப்பட்டது. வணிகர்: $name. UPI ID: $vpa." +
                    if (!p.amount.isNullOrBlank()) " தொகை: ${formatAmount(p.amount)} ரூபாய்." else ""
        else
            "QR code detected. Merchant: $name. UPI ID: $vpa." +
                    if (!p.amount.isNullOrBlank()) " Amount: ${formatAmount(p.amount)} rupees." else ""

        if (!p.amount.isNullOrBlank()) currentAmount = p.amount

        setStatus(
            "✅ $name",
            "UPI: $vpa${if (!p.amount.isNullOrBlank()) " | ₹${p.amount}" else ""}"
        )
        speak(announcement, UTT_QR_DETECTED)
    }

    private fun proceedAfterQRAnnouncement() {
        if (currentAmount != null) {
            proceedToConfirmation()
        } else {
            state = PaymentState.AMOUNT_NEEDED
            val name = payload?.payeeName
                ?: if (isTamil) "இந்த வணிகருக்கு" else "this merchant"
            val prompt = if (isTamil)
                "$name-க்கு எவ்வளவு ரூபாய் அனுப்ப வேண்டும்? சொல்லுங்கள்."
            else
                "Please say the amount you want to pay to $name."
            speak(prompt, UTT_AMOUNT_PROMPT)
            setStatus(str_amount_prompt_status, str_amount_sub)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Alternative input (no QR)
    // ══════════════════════════════════════════════════════════════════════

    private fun offerAlternativeInput() {
        state = PaymentState.UPIID_INPUT
        speak(str_upiid_prompt_tts, UTT_UPIID_PROMPT)
        setStatus(str_upiid_status)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Confirmation screen
    // ══════════════════════════════════════════════════════════════════════

    private fun proceedToConfirmation() {
        val amount = currentAmount?.toDoubleOrNull()

        if (amount != null && amount > simulatedBalance) {
            setStatus(str_balance_low_status)
            speak(str_balance_low_tts, UTT_BALANCE_LOW)
            return
        }

        if (amount != null && amount > UPI_LITE_MAX_AMOUNT) {
            setStatus(str_limit_warn_status)
            speak(str_limit_warn_tts, UTT_LIMIT_WARN)
            state = PaymentState.AMOUNT_NEEDED
            currentAmount = null
            return
        }

        val upiId = payload?.payeeVpa?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "payeeVpa null at confirmation! payload=$payload")
            ""
        }
        val name = payload?.payeeName?.takeIf { it.isNotBlank() }
            ?: if (isTamil) "வணிகர்" else "Merchant"
        val amt = formatAmount(currentAmount ?: "0")

        Log.d(TAG, "→ ConfirmationActivity: name=$name upiId=$upiId amt=$amt")

        state = PaymentState.DONE
        setStatus("✅ ${if (isTamil) "உறுதிப்படுத்தல் திரை…" else "Opening confirmation…"}", "₹$amt → $name")
        speak(str_opening_confirm, UTT_QR_DETECTED)

        handler.postDelayed({
            val intent = Intent(this, ConfirmationActivity::class.java).apply {
                putExtra(ConfirmationActivity.EXTRA_MERCHANT_NAME, name)
                putExtra(ConfirmationActivity.EXTRA_UPI_ID, upiId)
                putExtra(ConfirmationActivity.EXTRA_AMOUNT, amt)
                putExtra("IS_TAMIL", isTamil)
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
            REQ_AMOUNT       -> if (isTamil) "தொகை சொல்லுங்கள்" else "Say the amount in rupees"
            REQ_UPI_ID       -> if (isTamil) "UPI ID சொல்லுங்கள்" else "Say the UPI ID or contact name"
            REQ_CONTACT_NAME -> if (isTamil) "பெயர் சொல்லுங்கள்" else "Say the contact name"
            else             -> if (isTamil) "பேசுங்கள்" else "Speak now"
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // ✅ FIX 1: ta-IN for Tamil, en-IN for English
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, asrLocale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, asrLocale)
                // ✅ FIX 2: Prevent device falling back to its default language
                putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "Speech recognizer unavailable", e)
            setStatus("❌ ${if (isTamil) "மைக்ரோஃபோன் கிடைக்கவில்லை" else "Microphone unavailable"}")
            speak(str_mic_unavailable_tts, UTT_ERROR)
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
                speak(str_amount_retry_tts, UTT_AMOUNT_PROMPT)
                setStatus("🔁 ${if (isTamil) "மீண்டும் சொல்லுங்கள்" else "Didn't catch that"}", str_amount_sub)
            } else {
                retryCount = 0
                speak(str_amount_failed_tts, UTT_ERROR)
                setStatus("❌ ${if (isTamil) "குரல் கேட்கவில்லை" else "Voice input failed"}")
            }
        }

        retryCount = 0
        currentAmount = amount
        setStatus("✅ ${if (isTamil) "தொகை: ₹$amount" else "Amount: ₹$amount"}", "")
        proceedToConfirmation()
    }

    // ── UPI ID ──────────────────────────────────────────────────────────────

    private fun onUpiIdVoice(candidates: List<String>) {
        val raw = candidates[0].lowercase().trim()
        val vpa = reconstructUpiId(raw)

        if (vpa == null) {
            retryCount++
            return if (retryCount <= MAX_RETRY) {
                speak(str_upiid_retry_tts, UTT_UPIID_PROMPT)
            } else {
                retryCount = 0
                speak(str_upiid_failed_tts, UTT_ERROR)
                resetForNextScan()
            }
        }

        retryCount = 0
        payload = UpiPayload(
            payeeVpa = vpa, payeeName = vpa,
            amount = null, merchantCode = null, transactionRef = null
        )

        if (!isMerchantValid(payload!!)) {
            val msg = if (isTamil) "UPI ID $vpa சரியில்லை. மீண்டும் முயற்சிக்கவும்."
            else "The UPI ID $vpa appears invalid. Please try again."
            speak(msg, UTT_FRAUD_WARN)
            return
        }

        state = PaymentState.AMOUNT_NEEDED
        val msg = if (isTamil) "UPI ID $vpa சரி. இப்போது தொகை சொல்லுங்கள்."
        else "UPI ID set to $vpa. Please say the amount."
        speak(msg, UTT_AMOUNT_PROMPT)
        setStatus("✅ UPI: $vpa", str_amount_sub)
    }

    // ── Contact ─────────────────────────────────────────────────────────────

    private fun onContactVoice(candidates: List<String>) {
        val name = candidates[0].trim().replaceFirstChar { it.uppercase() }
        val resolvedVpa = resolveContactToVpa(name)
        if (resolvedVpa == null) {
            val msg = if (isTamil) "$name-க்கு UPI ID கிடைக்கவில்லை. மீண்டும் முயற்சிக்கவும்."
            else "Could not find a UPI ID for $name. Please try again or scan a QR code."
            speak(msg, UTT_ERROR)
            resetForNextScan()
            return
        }
        payload = UpiPayload(
            payeeVpa = resolvedVpa, payeeName = name,
            amount = null, merchantCode = null, transactionRef = null
        )
        state = PaymentState.AMOUNT_NEEDED
        val msg = if (isTamil) "$name-க்கு பணம் அனுப்புகிறோம். தொகை சொல்லுங்கள்."
        else "Paying $name at $resolvedVpa. Please say the amount."
        speak(msg, UTT_AMOUNT_PROMPT)
        setStatus("✅ ${if (isTamil) "தொடர்பு" else "Contact"}: $name", str_amount_sub)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Fraud / merchant validation
    // ══════════════════════════════════════════════════════════════════════

    private fun isMerchantValid(p: UpiPayload): Boolean {
        val vpa = p.payeeVpa ?: return false
        val vpaRegex = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z]{2,}$")
        if (!vpaRegex.matches(vpa)) return false
        p.amount?.let { if (it.toDoubleOrNull()?.let { d -> d <= 0 } == true) return false }
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
        // English word map
        val englishMap = mapOf(
            "five hundred" to "500", "four hundred" to "400", "three hundred" to "300",
            "two hundred" to "200", "one hundred" to "100", "hundred" to "100",
            "ninety" to "90", "eighty" to "80", "seventy" to "70", "sixty" to "60",
            "fifty" to "50", "forty" to "40", "thirty" to "30", "twenty" to "20",
            "nineteen" to "19", "eighteen" to "18", "seventeen" to "17", "sixteen" to "16",
            "fifteen" to "15", "fourteen" to "14", "thirteen" to "13", "twelve" to "12",
            "eleven" to "11", "ten" to "10", "nine" to "9", "eight" to "8",
            "seven" to "7", "six" to "6", "five" to "5", "four" to "4",
            "three" to "3", "two" to "2", "one" to "1"
        )

        // ✅ FIX 3: Full Tamil number map — both native script (ta-IN ASR) + transliterations
        val tamilMap = mapOf(
            // Tamil script — ta-IN ASR outputs these directly
            "ஆயிரம்"      to "1000", "ஆயிரத்து"     to "1000",
            "ஐந்நூறு"     to "500",  "ஐநூறு"         to "500",  "அய்நூறு"  to "500",
            "நானூறு"      to "400",  "முன்னூறு"      to "300",
            "இருநூறு"     to "200",  "நூற்றி"         to "100",
            "நூறு"         to "100",  "நூர்"           to "100",
            "தொண்ணூறு"   to "90",   "எண்பது"         to "80",
            "எழுபது"      to "70",   "அறுபது"         to "60",
            "ஐம்பது"      to "50",   "நாற்பது"        to "40",
            "முப்பது"     to "30",   "இருபது"         to "20",
            "பதினொன்பது" to "19",   "பதினெட்டு"     to "18",
            "பதினேழு"     to "17",   "பதினாறு"        to "16",
            "பதினைந்து"  to "15",   "பதினான்கு"     to "14",
            "பதின்மூன்று" to "13",  "பன்னிரண்டு"   to "12",
            "பதினொன்று"  to "11",   "பத்து"          to "10",
            "ஒன்பது"      to "9",    "எட்டு"          to "8",
            "ஏழு"          to "7",    "ஆறு"            to "6",
            "ஐந்து"       to "5",    "நான்கு"         to "4",
            "மூன்று"      to "3",    "இரண்டு"         to "2",   "ஒன்று" to "1",
            // Transliterations — en-IN / Tanglish / mixed speech fallback
            "aayiram"      to "1000", "thollayiram"    to "1000",
            "ainootru"     to "500",  "ainthu nooru"   to "500",  "ainu nooru" to "500",
            "naanootru"    to "400",  "munnooru"       to "300",
            "irunooru"     to "200",  "nootri"         to "100",
            "nooru"        to "100",  "noooru"         to "100",
            "thombathu"    to "90",   "enbathu"        to "80",
            "ezhubathu"    to "70",   "aRubathu"       to "60",   "arubathu" to "60",
            "aimpathu"     to "50",   "simpathu"       to "50",
            "naRpathu"     to "40",   "narpathu"       to "40",
            "muppathu"     to "30",   "iruppathu"      to "20",
            "pathon"       to "19",   "pathettu"       to "18",
            "pathinettu"   to "18",   "pathiN"         to "17",   "pathinezhu" to "17",
            "pathinaaru"   to "16",   "pathinaindu"    to "15",
            "padhinainthu" to "15",   "pathiNaalu"     to "14",
            "pathimoonu"   to "13",   "pannirandu"     to "12",
            "pathinondu"   to "11",   "pathu"          to "10",
            "onbathu"      to "9",    "ettu"           to "8",
            "ezhu"         to "7",    "aaru"           to "6",
            "ainthu"       to "5",    "naalu"          to "4",
            "moonu"        to "3",    "irandu"         to "2",    "onnu" to "1"
        )

        val lower = text.lowercase().trim()

        // Try Tamil map first (if isTamil) — sort by key length descending so
        // longer matches like "ஐந்நூறு" beat shorter ones like "ஐந்து"
        if (isTamil) {
            tamilMap.entries.sortedByDescending { it.key.length }
                .forEach { (word, num) -> if (lower.contains(word)) return num }
        }
        // Then English map
        englishMap.entries.sortedByDescending { it.key.length }
            .forEach { (word, num) -> if (lower.contains(word)) return num }
        // Finally try bare digits
        return Regex("""(\d+(?:\.\d{1,2})?)""").find(text)?.value
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
        val contacts = mapOf(
            "Amma"  to "amma@okaxis",
            "Appa"  to "appa@okhdfcbank",
            "Ravi"  to "ravi.k@ybl",
            "Priya" to "priya99@paytm",
            "Anna"  to "anna@upi",
            "Akka"  to "akka@okicici"
        )
        return contacts[name]
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
            speak(str_no_speech_tts, uttId)
            setStatus("🔁 ${if (isTamil) "மீண்டும் முயற்சிக்கவும்" else "Try again"}")
        } else {
            retryCount = 0
            speak(
                if (isTamil) "குரல் கேட்கவில்லை. QR மீண்டும் ஸ்கேன் செய்யவும்."
                else "Voice input failed. Please scan the QR code again.",
                UTT_ERROR
            )
            setStatus("❌ ${if (isTamil) "தோல்வி" else "Input failed"}")
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
        setStatus(str_scan_prompt, str_tap_manual)
        handler.postDelayed(alignmentRunnable, 6000)
        Log.d(TAG, "Reset complete")
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