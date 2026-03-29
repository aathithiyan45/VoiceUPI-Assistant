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
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * VoiceMainActivity — The core screen for visually impaired users.
 *
 * Voice commands:
 *   "scan qr"   → opens QRScannerActivity
 *   "send money"→ TTS "Feature coming soon"
 *   "help"      → TTS explains available commands
 *
 * On launch: auto-starts listening after welcome TTS finishes.
 */
class VoiceMainActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: ImageButton

    // ── TTS ────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    // ── Speech ─────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── Internal ───────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val tag = "VoiceMainActivity"

    // TTS utterance IDs
    private val UTT_WELCOME = "utt_welcome"
    private val UTT_PROMPT  = "utt_prompt"
    private val UTT_COMMAND = "utt_command"

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnMic   = findViewById(R.id.btnMic)

        setupTts()
        setupMicButton()
        checkAudioPermission()
    }

    override fun onResume() {
        super.onResume()
        // Re-announce when returning from another screen
        if (ttsReady && !isListening) {
            handler.postDelayed({ speakPrompt() }, 600)
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
        tts.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        tts.shutdown()
        speechRecognizer?.destroy()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Permission
    // ══════════════════════════════════════════════════════════════════════

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                initSpeechRecognizer()
            } else {
                setStatus("Microphone permission denied.\nPlease grant it in Settings.")
            }
        }

    private fun checkAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> initSpeechRecognizer()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TTS setup
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
                            if (utteranceId == UTT_WELCOME || utteranceId == UTT_PROMPT) {
                                startListening()
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                // Welcome message on first launch
                speak("Welcome to Voice UPI. Say scan QR to begin.", UTT_WELCOME)
            } else {
                Log.e(tag, "TTS init failed: $status")
                setStatus("Text-to-speech unavailable on this device.")
            }
        }
    }

    private fun speak(text: String, utteranceId: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun speakPrompt() {
        setStatus("Say a command…")
        speak("Say scan QR, send money, or help.", UTT_PROMPT)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Mic button
    // ══════════════════════════════════════════════════════════════════════

    private fun setupMicButton() {
        btnMic.contentDescription = "Microphone. Tap to speak a command."
        btnMic.setOnClickListener {
            tts.stop()
            if (isListening) {
                stopListening()
                speakPrompt()
            } else {
                startListening()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SpeechRecognizer
    // ══════════════════════════════════════════════════════════════════════

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition not available on this device.")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    private fun startListening() {
        if (isListening) return
        val recognizer = speechRecognizer ?: run {
            initSpeechRecognizer()
            speechRecognizer
        } ?: return

        isListening = true
        setStatus("Listening…")
        setMicActive(true)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        recognizer.startListening(intent)
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        setMicActive(false)
        speechRecognizer?.stopListening()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { setStatus("Listening…") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isListening = false
            setMicActive(false)
            setStatus("Processing…")
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            setMicActive(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                handleVoiceCommand(matches[0].lowercase().trim())
            } else {
                onVoiceError("No speech detected")
            }
        }

        override fun onError(error: Int) {
            isListening = false
            setMicActive(false)
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
                SpeechRecognizer.ERROR_AUDIO          -> "Audio error. Tap mic to retry."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error. Check connection."
                else -> "Could not understand. Tap mic to retry."
            }
            onVoiceError(msg)
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Command handling
    // ══════════════════════════════════════════════════════════════════════

    private fun handleVoiceCommand(spoken: String) {
        Log.d(tag, "Command spoken: $spoken")
        when {
            containsAny(spoken, "scan", "qr", "scan qr", "scanner") -> {
                setStatus("Opening QR scanner…")
                speak("Opening QR scanner.", UTT_COMMAND)
                handler.postDelayed({ openQrScanner() }, 1000)
            }
            containsAny(spoken, "send money", "send", "pay", "payment") -> {
                setStatus("Feature coming soon.")
                speak("Send money feature is coming soon.", UTT_COMMAND)
                handler.postDelayed({ speakPrompt() }, 2500)
            }
            containsAny(spoken, "help", "commands", "what can you do") -> {
                val helpText = "You can say: scan QR to scan a payment code, " +
                        "send money to transfer funds, or help to hear this message again."
                setStatus("Available commands:\n• Scan QR\n• Send Money\n• Help")
                speak(helpText, UTT_COMMAND)
                handler.postDelayed({ speakPrompt() }, 6000)
            }
            else -> {
                setStatus("Command not recognised.\nSay help for options.")
                speak("I did not understand. Say scan QR, send money, or help.", UTT_COMMAND)
                handler.postDelayed({ startListening() }, 3500)
            }
        }
    }

    private fun onVoiceError(message: String) {
        setStatus(message)
        speak(message, UTT_COMMAND)
        handler.postDelayed({ speakPrompt() }, 2500)
    }

    private fun containsAny(input: String, vararg keywords: String): Boolean =
        keywords.any { input.contains(it) }

    // ══════════════════════════════════════════════════════════════════════
    //  Navigation
    // ══════════════════════════════════════════════════════════════════════

    private fun openQrScanner() {
        startActivity(Intent(this, QRScannerActivity::class.java))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun setStatus(text: String) {
        tvStatus.text = text
        // Announce for TalkBack users
        tvStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun setMicActive(active: Boolean) {
        btnMic.isSelected = active
        btnMic.alpha = if (active) 1.0f else 0.75f
        btnMic.contentDescription = if (active) "Listening. Tap to stop." else "Microphone. Tap to speak."
    }
}