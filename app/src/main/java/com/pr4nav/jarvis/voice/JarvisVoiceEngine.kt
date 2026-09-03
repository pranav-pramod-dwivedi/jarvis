package com.pr4nav.jarvis.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
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
                applyMaleVoice()
                tts?.setSpeechRate(1.02f)
                tts?.setPitch(0.92f) // Deep, calm, natural masculine pitch (JARVIS style)
            }
        } else {
            Log.e(TAG, "TTS Initialization failed: status $status")
        }
    }

    private fun applyMaleVoice() {
        try {
            val voices = tts?.voices
            if (!voices.isNullOrEmpty()) {
                // Priority list of British & US Male voices (Paul Bettany / JARVIS style)
                val preferredMale = voices.firstOrNull { v ->
                    val name = v.name.lowercase()
                    val lang = v.locale.language.lowercase()
                    lang == "en" && (
                        name.contains("en-gb-x-rjs") || // Iconic British Male (Deep, clear)
                        name.contains("en-gb-x-gbb") || // British Male
                        name.contains("en-us-x-iom") || // US Male
                        name.contains("en-us-x-iob") || // US Male
                        name.contains("en-us-x-iol") || // US Male
                        name.contains("en-us-x-tpd")    // US Male
                    )
                } ?: voices.firstOrNull { v ->
                    val name = v.name.lowercase()
                    v.locale.language.equals("en", ignoreCase = true) && (
                        name.contains("male") || 
                        name.contains("-m-") || 
                        name.contains("george") || 
                        name.contains("guy") || 
                        name.contains("david")
                    )
                } ?: voices.firstOrNull { v ->
                    // Fallback to any English voice that is NOT female ("female", "-f-", "sfg", "ahp", "gba")
                    val name = v.name.lowercase()
                    v.locale.language.equals("en", ignoreCase = true) && 
                        !name.contains("female") && 
                        !name.contains("-f-") && 
                        !name.contains("sfg") && 
                        !name.contains("gba") &&
                        !name.contains("ahp")
                }

                if (preferredMale != null) {
                    tts?.voice = preferredMale
                    Log.i(TAG, "Selected Male TTS Voice: ${preferredMale.name} (${preferredMale.locale})")
                } else {
                    Log.w(TAG, "No specific male voice found in ${voices.size} available voices, using pitch 0.92f")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error applying male voice: ${e.message}")
        }
    }

    private var wordHighlightRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun speak(
        text: String,
        interrupt: Boolean = true,
        onWordSpoken: ((start: Int, end: Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (interrupt) {
            stopSpeaking()
        }

        val cleanText = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitizeForSpeech(text)
        if (cleanText.isBlank()) {
            onDone?.invoke()
            return
        }

        // Start progressive word highlighting timer synchronized to speech rate
        startWordHighlighting(cleanText, onWordSpoken)

        val wrappedOnDone: () -> Unit = {
            stopWordHighlighting()
            onDone?.invoke()
            Unit
        }

        // Android High-Definition Native TextToSpeech (Clean, Natural, Crystal-Clear Voice)
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "Android TTS engine not ready yet")
            wrappedOnDone()
            return
        }

        val speechRate = VoiceAssistantPreferences.getSpeechRate(context)
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(0.92f)
        applyMaleVoice()

        val utteranceId = "JARVIS_TTS_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    mainHandler.post { wrappedOnDone() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    mainHandler.post { wrappedOnDone() }
                }
            }
            override fun onRangeStart(utteranceIdParam: String?, start: Int, end: Int, frame: Int) {
                if (utteranceIdParam == utteranceId) {
                    mainHandler.post {
                        onWordSpoken?.invoke(start, end)
                    }
                }
            }
        })

        tts?.speak(cleanText, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun startWordHighlighting(cleanText: String, onWordSpoken: ((start: Int, end: Int) -> Unit)?) {
        stopWordHighlighting()
        if (onWordSpoken == null) return

        val words = Regex("\\S+").findAll(cleanText).toList()
        if (words.isEmpty()) return

        var cumulativeDelay = 80L
        val speechRate = VoiceAssistantPreferences.getSpeechRate(context).coerceIn(0.5f, 2.0f)
        val msPerChar = (42.0f / speechRate).toLong()

        val tasks = mutableListOf<Runnable>()
        for (m in words) {
            val start = m.range.first
            val end = m.range.last + 1
            val word = m.value
            val wordDuration = Math.max(160L, (word.length * msPerChar) + (if (word.endsWith(".") || word.endsWith(",")) 120L else 0L))

            val task = Runnable {
                onWordSpoken(start, end)
            }
            tasks.add(task)
            mainHandler.postDelayed(task, cumulativeDelay)
            cumulativeDelay += wordDuration
        }

        wordHighlightRunnable = Runnable {
            for (t in tasks) {
                mainHandler.removeCallbacks(t)
            }
        }
    }

    private fun stopWordHighlighting() {
        wordHighlightRunnable?.run()
        wordHighlightRunnable = null
    }

    fun stopSpeaking() {
        stopWordHighlighting()
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (_: Exception) {}
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

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
                    if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(targetContext)) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(targetContext)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(targetContext.applicationContext)
                    }
                } catch (e: Exception) {
                    try {
                        SpeechRecognizer.createSpeechRecognizer(targetContext.applicationContext)
                    } catch (_: Exception) { null }
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
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                }
                muteEarcons(targetContext, true)
                recognizer.startListening(intent)
            } catch (e: Exception) {
                isListening = false
                muteEarcons(targetContext, false)
                onError("Failed to start speech recognizer: ${e.message}")
            }
        }
    }

    private var isEarconsMuted = false
    private var savedSystemVol: Int? = null
    private var savedNotifVol: Int? = null

    fun muteEarcons(ctx: Context, mute: Boolean) {
        if (mute == isEarconsMuted) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val streams = intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
            if (mute) {
                savedSystemVol = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
                savedNotifVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                for (stream in streams) {
                    try { am.setStreamVolume(stream, 0, 0) } catch (_: Exception) {}
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) {}
                    }
                }
                isEarconsMuted = true
            } else {
                for (stream in streams) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try { am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
                    }
                }
                savedSystemVol?.let { try { am.setStreamVolume(AudioManager.STREAM_SYSTEM, it, 0) } catch (_: Exception) {} }
                savedNotifVol?.let { try { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, it, 0) } catch (_: Exception) {} }
                savedSystemVol = null
                savedNotifVol = null
                isEarconsMuted = false
            }
        } catch (_: Exception) {}
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            isListening = false
            context?.let { muteEarcons(it, false) }
        } catch (_: Exception) {}
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            context?.let { muteEarcons(it, false) }
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}
    }
}
