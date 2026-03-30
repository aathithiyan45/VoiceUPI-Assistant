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
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.Locale
import kotlin.math.*

// ══════════════════════════════════════════════════════════════════════════════
//  Constants
// ══════════════════════════════════════════════════════════════════════════════

private const val TAG = "VoiceUPI"
private const val REQ_AMOUNT       = 100
private const val REQ_UPI_ID       = 300
private const val REQ_CONTACT_NAME = 400
private const val MAX_RETRY = 2
private const val UPI_LITE_MAX_AMOUNT     = 500.0
private const val UPI_LITE_WALLET_BALANCE = 2000.0

private const val UTT_WELCOME        = "utt_welcome"
private const val UTT_QR_DETECTED    = "utt_qr_detected"
private const val UTT_AMOUNT_PROMPT  = "utt_amount_prompt"
private const val UTT_UPIID_PROMPT   = "utt_upiid_prompt"
private const val UTT_CONTACT_PROMPT = "utt_contact_prompt"
private const val UTT_ERROR          = "utt_error"
private const val UTT_LIMIT_WARN     = "utt_limit_warn"
private const val UTT_FRAUD_WARN     = "utt_fraud_warn"
private const val UTT_BALANCE_LOW    = "utt_balance_low"
private const val UTT_LANG_SWITCH    = "utt_lang_switch"
private const val UTT_DIRECTION      = "utt_direction"
private const val UTT_CENTERED       = "utt_centered"

private const val LOCK_INNER = 0.30f
private const val LOCK_OUTER = 0.70f
private const val STEADY_FRAMES_REQUIRED = 10
private const val GUIDANCE_COOLDOWN_MS = 1_500L
private const val DIST_VERY_FAR   = 0.10f
private const val DIST_VERY_CLOSE = 0.40f
private const val MIN_WIDTH_RELIABLE = 0.05f

private enum class PaymentState { IDLE, QR_DETECTED, AMOUNT_NEEDED, UPIID_INPUT, FRAUD_CHECK, DONE }

internal enum class AppLanguage(val locale: Locale, val label: String) {
    ENGLISH(Locale("en", "IN"), "English"),
    HINDI(Locale("hi", "IN"), "Hindi"),
    TAMIL(Locale("ta", "IN"), "Tamil")
}

private data class UpiPayload(
    val payeeVpa: String?, val payeeName: String?, val amount: String?,
    val merchantCode: String?, val transactionRef: String?
)

@SuppressLint("SetTextI18n")
class QRScannerActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scanOverlay: ScanOverlayView
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var currentLanguage = AppLanguage.ENGLISH

    private var state: PaymentState = PaymentState.IDLE
    private var payload: UpiPayload? = null
    private var currentAmount: String? = null
    private var isScanned = false
    private var retryCount = 0
    private var simulatedBalance = UPI_LITE_WALLET_BALANCE

    private var cameraProvider: ProcessCameraProvider? = null
    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private val sonar = SonarBeeper()

    @Volatile private var lastGuidanceTimeMs: Long = 0L
    @Volatile private var steadyFrameCount: Int = 0

    private var lastSearchPromptTime = 0L
    private var scanStartTime = 0L
    private var fallbackTriggered = false

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) startCamera()
        else {
            speak("Camera permission denied. I cannot scan without it.", UTT_ERROR)
            setStatus("❌ Camera Denied", "Grant permission in settings")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        sonar.start()
        buildUI()
        initTTS()
        if (hasCamera()) startCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        sonar.stopBeeping()
    }

    override fun onDestroy() {
        super.onDestroy()
        sonar.release()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }

    private fun buildUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        previewView = PreviewView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
        scanOverlay = ScanOverlayView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }

        statusText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).also { it.setMargins(0, 0, 0, 160) }
            textSize = 17f; setTextColor(-1); gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20); setBackgroundColor(0xCC000000.toInt())
            text = "📷 Point camera at UPI QR code"
        }

        subText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).also { it.setMargins(0, 0, 0, 80) }
            textSize = 13f; setTextColor(0xFFBBBBBB.toInt()); gravity = Gravity.CENTER
            setPadding(40, 8, 40, 8); setBackgroundColor(0xAA000000.toInt())
            text = "Tap anywhere to enter UPI ID manually"
        }

        root.addView(previewView); root.addView(scanOverlay); root.addView(statusText); root.addView(subText)
        setContentView(root)
        root.setOnClickListener { if (state == PaymentState.IDLE) offerAlternativeInput() }
    }

    /**
     * Corrected startCamera.
     * The OptIn is removed from the expression and moved to the function definition.
     */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val barcodeScanner = BarcodeScanning.getClient()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            imageAnalyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                analyzeFrame(barcodeScanner, proxy)
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * OptIn is correctly applied here to the entire function.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (scanStartTime == 0L) scanStartTime = now
        if (isScanned || state != PaymentState.IDLE) { proxy.close(); return }

        // 🔴 LEVEL 3: Fallback (10 Seconds)
        if (!fallbackTriggered && (now - scanStartTime > 10000)) {
            fallbackTriggered = true
            sonar.stopBeeping()
            speak("QR could not be found. Please ask the shop person to show the QR code clearly.", UTT_ERROR)
            setStatus("⚠️ Help Required", "Ask shopkeeper to show QR")
            proxy.close(); return
        }

        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val frameW = proxy.width.toFloat(); val frameH = proxy.height.toFloat()

        scanner.process(image).addOnSuccessListener { barcodes ->
            if (barcodes.isEmpty()) {
                // 🟢 LEVEL 1: Smart Search Mode
                if (now - lastSearchPromptTime > 4000) {
                    lastSearchPromptTime = now
                    speakDirection("Searching for QR code. Move your phone slowly.")
                    setStatus("🔍 Searching...", "Move phone slowly")
                }
                steadyFrameCount = 0; sonar.stopBeeping()
                scanOverlay.updateQrIndicator(null, frameW, frameH); return@addOnSuccessListener
            }

            // 🟡 LEVEL 2: Guidance
            scanStartTime = now
            val barcode = barcodes.maxByOrNull { it.boundingBox?.width() ?: 0 } ?: return@addOnSuccessListener
            val bbox = barcode.boundingBox ?: return@addOnSuccessListener
            scanOverlay.updateQrIndicator(bbox, frameW, frameH)

            val normCX = (bbox.left + bbox.right) / 2f / frameW
            val normCY = (bbox.top + bbox.bottom) / 2f / frameH
            val normW  = bbox.width().toFloat() / frameW
            val dxNorm = normCX - 0.5f; val dyNorm = normCY - 0.5f

            if (normW < DIST_VERY_FAR) {
                sonar.updateDistance(0.9f); speakDirection("QR is very far. Move much closer."); return@addOnSuccessListener
            }

            val normDist = sqrt((dxNorm/0.5f).pow(2) + (dyNorm/0.5f).pow(2)) / sqrt(2f)
            sonar.updateDistance(normDist.coerceIn(0f, 1f))

            val angleDeg = Math.toDegrees(atan2(dxNorm.toDouble(), -dyNorm.toDouble())).let { if (it < 0) it + 360.0 else it }
            val clockHour = clockFaceHour(angleDeg)

            if (!(normCX in LOCK_INNER..LOCK_OUTER && normCY in LOCK_INNER..LOCK_OUTER)) {
                steadyFrameCount = 0
                val dirHint = directionHintFromAngle(angleDeg, dxNorm, dyNorm)
                speakDirection("QR at $clockHour o'clock. $dirHint")
                setStatus("📷 QR at $clockHour o'clock", "Move to center")
            } else {
                steadyFrameCount++
                setStatus("✅ Hold steady... $steadyFrameCount", "Keep holding")
                if (steadyFrameCount >= STEADY_FRAMES_REQUIRED) {
                    isScanned = true; sonar.stopBeeping(); scanOverlay.showDetected(true)
                    hapticLockedIn(); speak("Locked. Processing payment.", UTT_CENTERED)
                    handler.postDelayed({ handleScannedQR(barcode.rawValue ?: "") }, 900)
                }
            }
        }.addOnCompleteListener { proxy.close() }
    }

    private fun clockFaceHour(angleDeg: Double): Int {
        val hour = ((angleDeg + 15.0) / 30.0).toInt() % 12
        return if (hour == 0) 12 else hour
    }

    private fun directionHintFromAngle(angleDeg: Double, dx: Float, dy: Float): String {
        val absDx = abs(dx); val absDy = abs(dy)
        return when {
            absDx > absDy * 1.8f -> if (dx > 0) "Move phone right." else "Move phone left."
            absDy > absDx * 1.8f -> if (dy > 0) "Move phone down."  else "Move phone up."
            else -> "Move phone ${if (dx > 0) "right" else "left"} and ${if (dy > 0) "down" else "up"}."
        }
    }

    private fun handleScannedQR(raw: String) {
        if (!raw.startsWith("upi://")) {
            speak("Not a UPI QR code.", UTT_ERROR); isScanned = false; return
        }
        val uri = Uri.parse(raw)
        payload = UpiPayload(uri.getQueryParameter("pa"), uri.getQueryParameter("pn"), uri.getQueryParameter("am"), null, null)
        state = PaymentState.QR_DETECTED
        val name = payload?.payeeName ?: "Merchant"
        speak("QR detected. Paying $name. ${if (payload?.amount != null) "Amount ${payload?.amount} rupees" else ""}", UTT_QR_DETECTED)
    }

    private fun proceedAfterQRAnnouncement() {
        if (payload?.amount != null) proceedToConfirmation()
        else { state = PaymentState.AMOUNT_NEEDED; speak("Say the amount.", UTT_AMOUNT_PROMPT) }
    }

    private fun proceedToConfirmation() {
        val intent = Intent(this, ConfirmationActivity::class.java).apply {
            putExtra(ConfirmationActivity.EXTRA_MERCHANT_NAME, payload?.payeeName)
            putExtra(ConfirmationActivity.EXTRA_AMOUNT, payload?.amount ?: currentAmount)
            putExtra(ConfirmationActivity.EXTRA_UPI_ID, payload?.payeeVpa)
        }
        startActivity(intent); finish()
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(currentLanguage.locale)
                ttsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) {}
                    override fun onDone(id: String?) {
                        handler.post { if (id == UTT_QR_DETECTED) proceedAfterQRAnnouncement()
                        else if (id == UTT_AMOUNT_PROMPT) launchVoiceInput(REQ_AMOUNT) }
                    }
                })
                speak("Welcome to Voice UPI. Point your camera at a QR code.", UTT_WELCOME)
            }
        }
    }

    private fun speak(text: String, id: String) {
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun speakDirection(text: String) {
        if (!ttsReady || tts.isSpeaking) return
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTT_DIRECTION) }
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, UTT_DIRECTION)
    }

    private fun setStatus(main: String, sub: String) {
        statusText.text = main
        subText.text = sub
        statusText.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun launchVoiceInput(code: Int) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, code)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == REQ_AMOUNT) {
            currentAmount = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            proceedToConfirmation()
        }
    }

    private fun offerAlternativeInput() {
        speak("Manual input coming soon. Please scan a QR code.", UTT_ERROR)
        setStatus("Tap to Scan", "Manual input coming soon")
    }

    private fun resetForNextScan() {
        isScanned = false; state = PaymentState.IDLE; steadyFrameCount = 0
        scanStartTime = 0L; lastSearchPromptTime = 0L; fallbackTriggered = false
        sonar.stopBeeping(); scanOverlay.showDetected(false)
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hapticLockedIn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 40, 40, 40, 40), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 40, 40, 40, 40, 40), -1)
        }
    }
}