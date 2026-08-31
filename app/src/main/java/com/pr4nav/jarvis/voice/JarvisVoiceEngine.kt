package com.pr4nav.jarvis.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * JARVIS Core Voice Engine
 * Handles Speech-to-Text (STT) and Text-to-Speech (TTS) with
 * instant interruptibility and natural cadence.
 */
class JarvisVoiceEngine(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "JarvisVoiceEngine"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val kokoroTts: KokoroTtsEngine by lazy { KokoroTtsEngine.getInstance(context) }
    @Volatile var isListening = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS Language not supported or missing data")
            } else {
                isTtsReady = true
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(0.98f)
            }
        } else {
            Log.e(TAG, "TTS Initialization failed: status $status")
        }
    }

    fun speak(text: String, interrupt: Boolean = true, onDone: (() -> Unit)? = null) {
        if (interrupt) {
            stopSpeaking()
        }

        // 1. Prioritize on-device Neural Kokoro-82M INT8 ONNX TTS
        if (KokoroTtsEngine.isModelInstalled(context)) {
            Log.i(TAG, "Speaking via Neural Kokoro-82M INT8 ONNX Engine: \"$text\"")
            kokoroTts.speak(text, speed = 1.0f, interrupt = interrupt, onDone = onDone)
            return
        }

        // 2. Fallback to Android System TextToSpeech
        if (!isTtsReady || tts == null) {
            onDone?.invoke()
            return
        }

        val utteranceId = "JARVIS_TTS_${System.currentTimeMillis()}"
        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) onDone()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) onDone()
                }
            })
        }

        tts?.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun stopSpeaking() {
        try {
            kokoroTts.stop()
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (_: Exception) {}
    }

    fun isSpeaking(): Boolean = kokoroTts.isSpeakingNow() || (tts?.isSpeaking == true)

    fun startListening(
        activity: Activity? = null,
        onPartial: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val targetContext = activity ?: context
        val hasAudio = try {
            targetContext.checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }

        if (!hasAudio) {
            onError("Microphone permission (RECORD_AUDIO) is not granted")
            return
        }

        val handler = Handler(Looper.getMainLooper())
        handler.post {
            if (activity != null && (activity.isFinishing || activity.isDestroyed)) return@post
            try {
                stopSpeaking() // Interrupt TTS before listening

                if (!SpeechRecognizer.isRecognitionAvailable(targetContext)) {
                    if (activity != null) {
                        // Fallback to RecognizerIntent dialog
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening to JARVIS...")
                        }
                        try {
                            activity.startActivityForResult(intent, 9091)
                        } catch (e: Exception) {
                            onError("Speech recognition dialog unavailable: ${e.message}")
                        }
                    } else {
                        onError("SpeechRecognizer is not available on this device")
                    }
                    return@post
                }

                try {
                    speechRecognizer?.destroy()
                } catch (_: Exception) {}
                speechRecognizer = null

                val recognizer = try {
                    SpeechRecognizer.createSpeechRecognizer(targetContext.applicationContext)
                } catch (e: Exception) {
                    null
                } ?: run {
                    onError("Failed to create SpeechRecognizer on this device")
                    return@post
                }

                speechRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing microphone permission"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            else -> "Voice recognition error ($error)"
                        }
                        onError(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            onResult(text)
                        } else {
                            onError("Could not recognize speech")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let { onPartial?.invoke(it) }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer.startListening(intent)
            } catch (e: Exception) {
                isListening = false
                onError("Failed to start speech recognizer: ${e.message}")
            }
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            isListening = false
        } catch (_: Exception) {}
    }

    fun destroy() {
        try {
            kokoroTts.destroy()
            speechRecognizer?.destroy()
            speechRecognizer = null
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}
    }
}
