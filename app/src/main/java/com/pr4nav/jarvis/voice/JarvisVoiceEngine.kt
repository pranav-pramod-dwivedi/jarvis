package com.pr4nav.jarvis.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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
 *
 * Primary TTS Engine: Kira 3.0 Flash TTS (kira-3.0-flash-tts) with
 * automatic offline fallback to Android High-Definition TextToSpeech.
 */
class JarvisVoiceEngine private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "JarvisVoiceEngine"

        @Volatile private var instance: JarvisVoiceEngine? = null

        fun getInstance(context: Context): JarvisVoiceEngine {
            return instance ?: synchronized(this) {
                instance ?: JarvisVoiceEngine(context.applicationContext).also { instance = it }
            }
        }

        fun isInitialized(): Boolean = instance != null
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var isKiraSpeaking = false
    @Volatile private var currentSynthesisJobId: Long = 0L
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile var isListening = false
        private set

    private constructor() : this(android.app.Application()) {
        throw UnsupportedOperationException("Use getInstance()")
    }

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
    private val audioCoordinator = JarvisAudioCoordinator(context)

    fun speak(
        text: String,
        interrupt: Boolean = true,
        onWordSpoken: ((start: Int, end: Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        val jobId = System.currentTimeMillis()
        currentSynthesisJobId = jobId

        if (interrupt) {
            stopSpeaking()
        }

        val cleanText = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitizeForSpeech(text)
        if (cleanText.isBlank()) {
            onDone?.invoke()
            return
        }

        val wrappedOnDone: () -> Unit = {
            stopWordHighlighting()
            audioCoordinator.abandonAssistantFocus()
            onDone?.invoke()
            Unit
        }

        audioCoordinator.requestAssistantFocus {
            stopSpeaking()
        }

        // 1. Primary Engine: Kira 3.0 Flash TTS (kira-3.0-flash-tts) for HUD and all voice output
        if (KiraTtsClient.isAvailable(context)) {
            val cloudVoice = VoiceAssistantPreferences.getCloudTtsVoice(context)
            Log.d(TAG, "Synthesizing voice via primary kira-3.0-flash-tts engine...")
            KiraTtsClient.synthesizeSpeechAsync(
                context = context,
                text = cleanText,
                voice = cloudVoice,
                onSuccess = { audioResult ->
                    mainHandler.post {
                        if (currentSynthesisJobId != jobId) {
                            Log.d(TAG, "Kira speech job superseded by newer utterance")
                            return@post
                        }
                        playKiraAudio(audioResult, cleanText, onWordSpoken, wrappedOnDone)
                    }
                },
                onError = { err ->
                    Log.w(TAG, "Kira TTS error: $err; falling back to local Android TTS engine")
                    mainHandler.post {
                        if (currentSynthesisJobId == jobId) {
                            speakWithLocalTts(cleanText, interrupt = false, onWordSpoken, wrappedOnDone)
                        }
                    }
                }
            )
        } else {
            // 2. Offline Fallback: Local Android High-Definition TextToSpeech ("until network is gone")
            Log.d(TAG, "Kira TTS unavailable (offline or missing key); using local Android TTS fallback")
            speakWithLocalTts(cleanText, interrupt = false, onWordSpoken, wrappedOnDone)
        }
    }

    private fun playKiraAudio(
        result: KiraTtsClient.TtsAudioResult,
        cleanText: String,
        onWordSpoken: ((start: Int, end: Int) -> Unit)?,
        onDone: () -> Unit
    ) {
        try {
            stopSpeakingKira()
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(result.audioFile.absolutePath)
                setOnCompletionListener {
                    isKiraSpeaking = false
                    stopSpeakingKira()
                    mainHandler.post { onDone() }
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error ($what, $extra); falling back to local TTS")
                    isKiraSpeaking = false
                    stopSpeakingKira()
                    speakWithLocalTts(cleanText, interrupt = false, onWordSpoken, onDone)
                    true
                }
                prepare()
            }
            mediaPlayer = mp
            isKiraSpeaking = true
            startWordHighlightingTimed(cleanText, result.durationMs, onWordSpoken)
            mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play Kira TTS audio: ${e.message}; falling back to local TTS", e)
            isKiraSpeaking = false
            stopSpeakingKira()
            speakWithLocalTts(cleanText, interrupt = false, onWordSpoken, onDone)
        }
    }

    private fun speakWithLocalTts(
        cleanText: String,
        interrupt: Boolean,
        onWordSpoken: ((start: Int, end: Int) -> Unit)?,
        wrappedOnDone: () -> Unit
    ) {
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "Android TTS engine not ready yet")
            wrappedOnDone()
            return
        }

        startWordHighlighting(cleanText, onWordSpoken)

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

    private fun startWordHighlightingTimed(
        cleanText: String,
        durationMs: Long,
        onWordSpoken: ((start: Int, end: Int) -> Unit)?
    ) {
        stopWordHighlighting()
        if (onWordSpoken == null) return

        val words = Regex("\\S+").findAll(cleanText).toList()
        if (words.isEmpty()) return

        val totalChars = words.sumOf { it.value.length }.coerceAtLeast(1)
        val usableDuration = durationMs.coerceAtLeast(words.size * 100L)

        var cumulativeDelay = 40L
        val tasks = mutableListOf<Runnable>()

        for (m in words) {
            val start = m.range.first
            val end = m.range.last + 1
            val word = m.value
            val fraction = word.length.toFloat() / totalChars.toFloat()
            val wordDuration = Math.max(100L, (fraction * usableDuration).toLong() + (if (word.endsWith(".") || word.endsWith(",")) 80L else 0L))

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

    private fun stopSpeakingKira() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        isKiraSpeaking = false
    }

    fun stopSpeaking() {
        currentSynthesisJobId = 0L
        stopWordHighlighting()
        stopSpeakingKira()
        audioCoordinator.abandonAssistantFocus()
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (_: Exception) {}
    }

    fun isSpeaking(): Boolean = isKiraSpeaking || (tts?.isSpeaking == true)

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
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                }
                audioCoordinator.silenceEarconForStart(intent)

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        audioCoordinator.restoreEarconAfterStart()
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                        audioCoordinator.silenceEarconForEnd(intent)
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        audioCoordinator.restoreEarconAfterResult()
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
                        audioCoordinator.restoreEarconAfterResult()
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

                recognizer.startListening(intent)
            } catch (e: Exception) {
                isListening = false
                audioCoordinator.restoreEarconAfterResult()
                onError("Failed to start speech recognizer: ${e.message}")
            }
        }
    }

    fun stopListening() {
        try {
            audioCoordinator.restoreEarconAfterResult()
            speechRecognizer?.stopListening()
            isListening = false
        } catch (_: Exception) {}
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            audioCoordinator.release()
            stopSpeakingKira()
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun destroyFully() {
        destroy()
        stopSpeakingKira()
        try {
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}
        instance = null
    }
}
