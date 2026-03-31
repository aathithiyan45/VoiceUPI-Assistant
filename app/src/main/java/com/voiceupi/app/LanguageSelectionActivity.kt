package com.voiceupi.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class LanguageSelectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LANG = "LANG"
        const val LANG_TAMIL   = "ta"
        const val LANG_ENGLISH = "en"
    }

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAction: (() -> Unit)? = null

    private val PROMPT_EN = "Choose your language. Double tap Tamil for Tamil, or English for English."
    private val PROMPT_TA = "மொழியை தேர்வு செய்யவும். தமிழுக்கு தமிழ் பொத்தானை அழுத்தவும். ஆங்கிலத்திற்கு English பொத்தானை அழுத்தவும்."
    private val UTT_ACTION = "utt_action"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_selection)

        val btnTamil   = findViewById<Button>(R.id.btnTamil)
        val btnEnglish = findViewById<Button>(R.id.btnEnglish)

        btnTamil.setOnClickListener   { launchVoiceMain(LANG_TAMIL)   }
        btnEnglish.setOnClickListener { launchVoiceMain(LANG_ENGLISH) }

        btnTamil.contentDescription   = "தமிழ் மொழி தேர்வு. Tamil language."
        btnEnglish.contentDescription = "English language. ஆங்கில மொழி தேர்வு."

        setupTts()
    }

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        handler.post {
                            if (id == "prompt_en") {
                                speakTamil()
                            } else if (id == UTT_ACTION) {
                                pendingAction?.invoke()
                                pendingAction = null
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        handler.post { pendingAction = null }
                    }
                })
                handler.postDelayed({ speakEnglish() }, 700)
            }
        }
    }

    private fun speakEnglish() {
        if (!ttsReady) return
        tts.language = Locale("en", "IN")
        tts.speak(PROMPT_EN, TextToSpeech.QUEUE_FLUSH, null, "prompt_en")
    }

    private fun speakTamil() {
        if (!ttsReady) return
        val result = tts.setLanguage(Locale("ta", "IN"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.language = Locale("en", "IN")
            tts.speak("Tamil selected. Tap Tamil button for Tamil language.", TextToSpeech.QUEUE_FLUSH, null, "prompt_ta")
        } else {
            tts.speak(PROMPT_TA, TextToSpeech.QUEUE_FLUSH, null, "prompt_ta")
        }
    }

    private fun speakAndThen(text: String, action: () -> Unit) {
        if (!ttsReady) {
            action()
            return
        }
        pendingAction = action
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTT_ACTION)
    }

    private fun launchVoiceMain(lang: String) {
        val confirmMsg = if (lang == LANG_TAMIL) "தமிழ் தேர்ந்தெடுக்கப்பட்டது" else "English selected"
        if (lang == LANG_TAMIL) tts.setLanguage(Locale("ta", "IN")) else tts.setLanguage(Locale("en", "IN"))
        
        speakAndThen(confirmMsg) {
            val intent = Intent(this, VoiceMainActivity::class.java).apply {
                putExtra(EXTRA_LANG, lang)
            }
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}