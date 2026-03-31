package com.voiceupi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
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
private const val UTT_ACTION         = "utt_action"
private const val UTT_FOUND_ANY      = "utt_found_any"
private const val UTT_HUMAN_ASSIST   = "utt_human_assist"

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
    private var pendingAction: (() -> Unit)? = null

    // ── State ──────────────────────────────────────────────────────────────
    private var state: PaymentState = PaymentState.IDLE
    private var payload: UpiPayload? = null
    private var currentAmount: String? = null
    private var isScanned = false
    private var retryCount = 0
    private var simulatedBalance = UPI_LITE_WALLET_BALANCE

    private var hasFoundAnyQr = false
    private var searchStartTime = 0L
    private var humanAssistTriggered = false

    // ── Camera ─────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null

    // ── Threading & haptic ─────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null

    // ── Alignment guidance ─────────────────────────────────────────────────
    private var lastGuidanceTime = 0L
    private val GUIDANCE_INTERVAL = 3500L 

    // ══════════════════════════════════════════════════════════════════════
    //  Localised strings
    // ══════════════════════════════════════════════════════════════════════

    private val str_welcome get() = if (isTamil)
        "Voice UPI-க்கு வரவேர்கிறோம். QR கோடை தேடிக்கொண்டிருக்கிறேன். போனை மெதுவாக நகர்த்துங்கள்."
    else
        "Welcome to Voice UPI. Searching for QR code. Move your phone slowly."

    private val str_qr_found_tts get() = if (isTamil)
        "QR கோடு கிடைத்தது! இப்போது சரியாக மையப்படுத்தவும்."
    else "QR code found! Now align it to the center."

    private val str_human_assist_tts get() = if (isTamil)
        "QR கோடை கண்டறிய முடியவில்லை. கடைக்காரரிடம் உதவி கேளுங்கள்."
    else "Cannot find the QR code. Please ask the shop person to help show it."

    private val str_scan_prompt get() = if (isTamil)
        "📷 QR கோடை தேடுகிறேன்…"
    else "📷 Searching for QR code…"

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
        
        searchStartTime = System.currentTimeMillis()
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
                override fun onError(id: String?) {
                    Log.e(TAG, "TTS error: $id")
                    handler.post { pendingAction = null }
                }
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
                            UTT_BALANCE_LOW,
                            UTT_SUCCESS, UTT_CANCEL      -> resetForNextScan()
                            UTT_ACTION -> {
                                pendingAction?.invoke()
                                pendingAction = null
                            }
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
        if (!::tts.isInitialized || !ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Reusable helper to execute an action only after speech finishes. */
    private fun speakAndThen(text: String, action: () -> Unit) {
        if (!::tts.isInitialized || !ttsReady) {
            action()
            return
        }
        pendingAction = action
        speak(text, UTT_ACTION)
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
        
        // Human Assist Mode Check
        val elapsed = System.currentTimeMillis() - searchStartTime
        if (!hasFoundAnyQr && elapsed > 15000 && !humanAssistTriggered) {
            humanAssistTriggered = true
            speak(str_human_assist_tts, UTT_HUMAN_ASSIST)
        }

        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isEmpty()) {
                    provideSearchingHaptic()
                } else {
                    for (barcode in barcodes) {
                        val bounds = barcode.boundingBox ?: continue
                        
                        // Smart Feedback: Found ANY QR
                        if (!hasFoundAnyQr) {
                            hasFoundAnyQr = true
                            hapticPulse(longArrayOf(0, 150)) // Strong vibration
                            speak(str_qr_found_tts, UTT_FOUND_ANY)
                        }

                        // Active guiding logic
                        if (!isCenter(bounds, proxy.width, proxy.height)) {
                            guideUser(bounds, proxy.width, proxy.height)
                            provideSearchingHaptic()
                        } else {
                            val raw = barcode.rawValue ?: continue
                            isScanned = true
                            hapticPulse(longArrayOf(0, 250)) // Final success haptic
                            scanOverlay.showDetected(true)
                            handleScannedQR(raw)
                            break
                        }
                    }
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "Scan fail", e) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun provideSearchingHaptic() {
        // Short light tick to indicate it's searching
        if (System.currentTimeMillis() % 1200 < 50) {
            hapticPulse(longArrayOf(0, 15))
        }
    }

    private fun isCenter(bounds: Rect, frameWidth: Int, frameHeight: Int): Boolean {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val frameCenterX = frameWidth / 2
        val frameCenterY = frameHeight / 2
        
        // Allowed tolerance (approx 15% of frame size)
        val toleranceX = frameWidth * 0.15
        val toleranceY = frameHeight * 0.15
        
        return Math.abs(centerX - frameCenterX) < toleranceX &&
               Math.abs(centerY - frameCenterY) < toleranceY
    }

    private fun guideUser(bounds: Rect, frameWidth: Int, frameHeight: Int) {
        val now = System.currentTimeMillis()
        if (now - lastGuidanceTime < GUIDANCE_INTERVAL) return
        
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val frameCenterX = frameWidth / 2
        val frameCenterY = frameHeight / 2

        // Check if too far (size based guessing)
        if (bounds.width() < frameWidth * 0.25) {
            lastGuidanceTime = now
            val text = if (isTamil) "இன்னும் அருகில் கொண்டு வாருங்கள்" else "Bring the phone closer"
            setStatus("📷 ${if (isTamil) "அருகில் வாருங்கள்" else "Bring closer"}", text)
            speak(text, UTT_ALIGN_GUIDE)
            return
        }

        val guideText = when {
            centerX < frameCenterX - (frameWidth * 0.1) -> if (isTamil) "போனை வலதுபுறம் நகர்த்தவும்" else "Move phone to the right"
            centerX > frameCenterX + (frameWidth * 0.1) -> if (isTamil) "போனை இடதுபுறம் நகர்த்தவும்" else "Move phone to the left"
            centerY < frameCenterY - (frameHeight * 0.1) -> if (isTamil) "போனை கீழே நகர்த்தவும்" else "Move phone down"
            centerY > frameCenterY + (frameHeight * 0.1) -> if (isTamil) "போனை மேலே நகர்த்தவும்" else "Move phone up"
            else -> null
        }

        if (guideText != null) {
            lastGuidanceTime = now
            setStatus("📷 ${if (isTamil) "நகர்த்தவும்" else "Move phone"}", guideText)
            speak(guideText, UTT_ALIGN_GUIDE)
        }
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
            "QR கோடு கண்டறியப்பட்டது. $name-க்கு பணம் அனுப்புகிறோம்." +
                    if (!p.amount.isNullOrBlank()) " தொகை: ${formatAmount(p.amount)} ரூபாய்." else ""
        else
            "QR code detected. Paying to $name." +
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

        val upiId = payload?.payeeVpa?.takeIf { it.isNotBlank() } ?: ""
        val name = payload?.payeeName?.takeIf { it.isNotBlank() }
            ?: if (isTamil) "வணிகர்" else "Merchant"
        val amt = formatAmount(currentAmount ?: "0")

        state = PaymentState.DONE
        setStatus("✅ ${if (isTamil) "உறுதிப்படுத்தல் திரை…" else "Opening confirmation…"}", "₹$amt → $name")
        
        speakAndThen(str_opening_confirm) {
            val intent = Intent(this, ConfirmationActivity::class.java).apply {
                putExtra(ConfirmationActivity.EXTRA_MERCHANT_NAME, name)
                putExtra(ConfirmationActivity.EXTRA_UPI_ID, upiId)
                putExtra(ConfirmationActivity.EXTRA_AMOUNT, amt)
                putExtra("IS_TAMIL", isTamil)
            }
            startActivity(intent)
            finish()
        }
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, asrLocale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, asrLocale)
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
        payload = UpiPayload(vpa, vpa, null, null, null)

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
        payload = UpiPayload(resolvedVpa, name, null, null, null)
        state = PaymentState.AMOUNT_NEEDED
        val msg = if (isTamil) "$name-க்கு பணம் அனுப்புகிறோம். தொகை சொல்லுங்கள்."
        else "Paying $name at $resolvedVpa. Please say the amount."
        speak(msg, UTT_AMOUNT_PROMPT)
        setStatus("✅ ${if (isTamil) "தொடர்பு" else "Contact"}: $name", str_amount_sub)
    }

    private fun isMerchantValid(p: UpiPayload): Boolean {
        val vpa = p.payeeVpa ?: return false
        val vpaRegex = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z]{2,}$")
        if (!vpaRegex.matches(vpa)) return false
        p.amount?.let { if (it.toDoubleOrNull()?.let { d -> d <= 0 } == true) return false }
        val blocklist = listOf("test@", "fake@", "dummy@", "scam@")
        if (blocklist.any { vpa.lowercase().startsWith(it) }) return false
        return true
    }

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
            UpiPayload(null, null, null, null, null)
        }
    }

    private fun extractAmount(text: String): String? {
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
        val tamilMap = mapOf(
            "ஆயிரம்" to "1000", "ஐந்நூறு" to "500", "ஐநூறு" to "500", "நானூறு" to "400",
            "முன்னூறு" to "300", "இருநூறு" to "200", "நூறு" to "100", "பத்து" to "10"
        )
        val lower = text.lowercase().trim()
        if (isTamil) {
            tamilMap.entries.sortedByDescending { it.key.length }
                .forEach { (word, num) -> if (lower.contains(word)) return num }
        }
        englishMap.entries.sortedByDescending { it.key.length }
            .forEach { (word, num) -> if (lower.contains(word)) return num }
        return Regex("""(\d+(?:\.\d{1,2})?)""").find(text)?.value
    }

    private fun formatAmount(amount: String): String {
        val d = amount.toDoubleOrNull() ?: return amount
        return if (d == kotlin.math.floor(d)) d.toLong().toString() else "%.2f".format(d)
    }

    private fun reconstructUpiId(spoken: String): String? {
        val normalized = spoken
            .replace(" at the rate of ", "@").replace(" at the rate ", "@").replace(" at ", "@")
            .replace(" ", "")
        val vpaRegex = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z]{2,}$")
        return if (vpaRegex.matches(normalized)) normalized else null
    }

    private fun resolveContactToVpa(name: String): String? {
        val contacts = mapOf("Amma" to "amma@okaxis", "Appa" to "appa@okhdfcbank")
        return contacts[name]
    }

    private fun handleVoiceError(requestCode: Int, reason: String) {
        retryCount++
        if (retryCount <= MAX_RETRY) {
            val uttId = when (requestCode) {
                REQ_AMOUNT -> UTT_AMOUNT_PROMPT
                REQ_UPI_ID -> UTT_UPIID_PROMPT
                else       -> UTT_ERROR
            }
            speak(str_no_speech_tts, uttId)
            setStatus("🔁 ${if (isTamil) "மீண்டும் முயற்சிக்கவும்" else "Try again"}")
        } else {
            retryCount = 0
            speak(if (isTamil) "குரல் கேட்கவில்லை. மீண்டும் ஸ்கேன் செய்யவும்." else "Voice input failed. Scan again.", UTT_ERROR)
            setStatus("❌ ${if (isTamil) "தோல்வி" else "Input failed"}")
        }
    }

    private fun resetForNextScan() {
        isScanned = false; payload = null; currentAmount = null; retryCount = 0
        state = PaymentState.IDLE; hasFoundAnyQr = false; humanAssistTriggered = false
        searchStartTime = System.currentTimeMillis()
        scanOverlay.showDetected(false); setStatus(str_scan_prompt, str_tap_manual)
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