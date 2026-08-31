package com.pr4nav.jarvis.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pr4nav.jarvis.MainActivity
import com.pr4nav.jarvis.router.JarvisIntentRouter
import java.util.Locale

/**
 * Foreground Service powering JARVIS Hands-Free Voice Assistant.
 *
 * Runs strictly as an Android-compliant Foreground Service with type MICROPHONE.
 * Separates Voice Activity Detection (VAD) from Wake Word verification and active STT.
 *
 * Explicit State Machine:
 * - OFF
 * - IDLE (quiet low-power candidate VAD)
 * - WAKE_DETECTED (verified wake word)
 * - STARTING_LISTENER
 * - LISTENING (active intentional SpeechRecognizer session)
 * - PROCESSING
 * - SPEAKING
 * - FOLLOW_UP_LISTENING
 * - PAUSED
 * - ERROR
 * - PERMISSION_REQUIRED
 */
class JarvisVoiceService : Service() {

    enum class VoiceState {
        OFF,
        IDLE,
        WAKE_DETECTED,
        STARTING_LISTENER,
        LISTENING,
        PROCESSING,
        SPEAKING,
        FOLLOW_UP_LISTENING,
        PAUSED,
        ERROR,
        PERMISSION_REQUIRED
    }

    companion object {
        private const val TAG = "JarvisVoiceService"
        private const val CHANNEL_ID = "jarvis_voice_assistant_channel"
        private const val NOTIFICATION_ID = 4040

        const val ACTION_START = "com.pr4nav.jarvis.voice.START"
        const val ACTION_STOP = "com.pr4nav.jarvis.voice.STOP"
        const val ACTION_PAUSE = "com.pr4nav.jarvis.voice.PAUSE"
        const val ACTION_RESUME = "com.pr4nav.jarvis.voice.RESUME"
        const val ACTION_START_ACTIVE_SESSION = "com.pr4nav.jarvis.voice.START_ACTIVE_SESSION"

        @Volatile var currentState: VoiceState = VoiceState.OFF
            private set
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerActiveSession(context: Context, reason: String = "User Trigger") {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_START_ACTIVE_SESSION
                putExtra("reason", reason)
            }
            context.startService(intent)
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceEngine: JarvisVoiceEngine? = null
    private var acousticDetector: AcousticWakeDetector? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var inConversationWindow = false
    private var conversationTimerRunnable: Runnable? = null
    private var backoffRunnable: Runnable? = null
    private var activeSttSessionRunning = false
    private var errorRetryCount = 0

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        voiceEngine = JarvisVoiceEngine(applicationContext)
        createNotificationChannel()

        try {
            val notif = buildNotification("Hands-Free Ready")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in startForeground during onCreate: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownService()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                updateState(VoiceState.PAUSED, "Paused by user")
                stopActiveSttSession("User paused")
                stopAcousticDetector()
                return START_STICKY
            }
            ACTION_RESUME -> {
                returnToIdleState("User resumed")
                return START_STICKY
            }
            ACTION_START_ACTIVE_SESSION -> {
                val reason = intent.getStringExtra("reason") ?: "Intentional trigger"
                startDeliberateListeningSession(reason)
                return START_STICKY
            }
            else -> {
                checkPermissionsAndInitialize()
                return START_STICKY
            }
        }
    }

    private fun checkPermissionsAndInitialize() {
        val hasMic = checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "checkPermissionsAndInitialize: hasMic=$hasMic")

        if (!hasMic) {
            updateState(VoiceState.PERMISSION_REQUIRED, "Microphone permission required")
            VoiceInstrumentation.log("INIT_BLOCKED", "Missing RECORD_AUDIO", currentState.name)
            return
        }

        returnToIdleState("Service started")
    }

    private fun updateState(state: VoiceState, detail: String) {
        currentState = state
        Log.i(TAG, "State transition -> $state ($detail)")
        val notif = buildNotification(detail)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notif)
    }

    /**
     * IDLE: SpeechRecognizer is NOT active.
     * Starts lightweight acoustic detector waiting for speech or wake trigger.
     */
    private fun returnToIdleState(reason: String) {
        mainHandler.post {
            if (!isRunning || currentState == VoiceState.PAUSED) return@post

            stopActiveSttSession(reason)
            inConversationWindow = false
            errorRetryCount = 0
            updateState(VoiceState.IDLE, "JARVIS ready")
            VoiceInstrumentation.log("ENTER_IDLE", reason, currentState.name)

            // Start low-power acoustic monitoring
            startAcousticDetector()
        }
    }

    private fun startAcousticDetector() {
        stopAcousticDetector()
        val wakeEngine = WakeWordEngineManager.getActiveEngine(applicationContext)
        Log.i(TAG, "startAcousticDetector: wakeEngine=${wakeEngine.name}, isInstalled=${wakeEngine.isInstalled}")

        acousticDetector = AcousticWakeDetector(
            context = applicationContext,
            wakeWordEngine = wakeEngine,
            onWakeWordDetected = { wakeWord ->
                mainHandler.post {
                    if (currentState == VoiceState.IDLE) {
                        VoiceInstrumentation.onWakeWordConfirmed(wakeWord, currentState.name)
                        updateState(VoiceState.WAKE_DETECTED, "Wake word confirmed: $wakeWord")

                        // Open AgentActivity chat screen automatically
                        try {
                            val chatIntent = Intent(applicationContext, com.pr4nav.jarvis.AgentActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("from_wake_word", true)
                                putExtra("wake_word", wakeWord)
                            }
                            applicationContext.startActivity(chatIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to launch chat screen on wake word: ${e.message}")
                        }

                        startDeliberateListeningSession("Wake word confirmed: $wakeWord")
                    }
                }
            },
            onVoiceActivityCandidate = {
                // Log voice activity candidate event without starting SpeechRecognizer
                VoiceInstrumentation.onVadEvent("Ambient voice candidate detected", currentState.name)
            }
        )
        acousticDetector?.start()
    }

    private fun stopAcousticDetector() {
        try {
            acousticDetector?.stop()
            acousticDetector = null
        } catch (_: Exception) {}
    }

    /**
     * Starts ONE intentional SpeechRecognizer session.
     * Does NOT loop indefinitely or restart on silence.
     */
    fun startDeliberateListeningSession(reason: String) {
        mainHandler.post {
            if (!isRunning || currentState == VoiceState.PAUSED || currentState == VoiceState.OFF) return@post
            if (activeSttSessionRunning) {
                Log.d(TAG, "SpeechRecognizer already running, ignoring redundant start request")
                return@post
            }

            stopAcousticDetector()
            updateState(
                if (inConversationWindow) VoiceState.FOLLOW_UP_LISTENING else VoiceState.LISTENING,
                "🎙 Listening..."
            )
            VoiceInstrumentation.onListenerStart(reason, currentState.name)
            activeSttSessionRunning = true

            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Recognizer ready for speech")
                    }

                    override fun onBeginningOfSpeech() {
                        // Barge-in check if TTS is still speaking
                        if (VoiceAssistantPreferences.isBargeInEnabled(applicationContext) &&
                            voiceEngine?.isSpeaking() == true) {
                            voiceEngine?.stopSpeaking()
                        }
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.d(TAG, "Speech input ended")
                    }

                    override fun onError(error: Int) {
                        activeSttSessionRunning = false
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions missing"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            else -> "Recognition error ($error)"
                        }
                        VoiceInstrumentation.onError(error, errorMsg, currentState.name)

                        // Do NOT rapidly restart! Enter controlled backoff to IDLE.
                        handleSttErrorWithBackoff(error, errorMsg)
                    }

                    override fun onResults(results: Bundle?) {
                        activeSttSessionRunning = false
                        VoiceInstrumentation.onListenerStop("Results received", currentState.name)

                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim().orEmpty()

                        if (text.isNotBlank()) {
                            VoiceInstrumentation.onSuccess(text, currentState.name)
                            handleRecognizedText(text)
                        } else {
                            returnToIdleState("Empty recognition result")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            // Barge-in check
                            if (VoiceAssistantPreferences.isBargeInEnabled(applicationContext) &&
                                (WakeWordEngine.isStopCommand(partial) || WakeWordEngine.containsWakeWord(partial))) {
                                voiceEngine?.stopSpeaking()
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    val lang = when (VoiceAssistantPreferences.getLanguage(applicationContext)) {
                        "hi" -> Locale("hi", "IN")
                        "en" -> Locale.US
                        else -> Locale.getDefault()
                    }
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Generous speech pause timeout (allow natural speaking pauses)
                    putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2000L)
                    putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 1500L)
                    putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 1500L)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                activeSttSessionRunning = false
                Log.e(TAG, "Failed to start speechRecognizer: ${e.message}")
                handleSttErrorWithBackoff(-1, e.message ?: "Exception")
            }
        }
    }

    private fun handleSttErrorWithBackoff(errorCode: Int, errorMsg: String) {
        errorRetryCount++
        updateState(VoiceState.ERROR, "STT: $errorMsg")

        // Controlled exponential backoff (e.g. 1.5s, 3s, 5s) instead of immediate infinite loop
        val backoffDelay = minOf(1500L * errorRetryCount, 5000L)
        backoffRunnable?.let { mainHandler.removeCallbacks(it) }
        backoffRunnable = Runnable {
            returnToIdleState("Backoff expired ($errorMsg)")
        }
        mainHandler.postDelayed(backoffRunnable!!, backoffDelay)
    }

    private fun handleRecognizedText(text: String) {
        Log.i(TAG, "Processing utterance: \"$text\"")

        // 1. Barge-in / stop check
        if (WakeWordEngine.isStopCommand(text)) {
            voiceEngine?.stopSpeaking()
            inConversationWindow = false
            returnToIdleState("User commanded stop")
            return
        }

        // 2. Wake-word check
        val hasWake = WakeWordEngine.containsWakeWord(text)
        // If wake-word was confirmed by on-device neural engine to trigger this STT session,
        // or user explicitly included "Jarvis", or in conversation follow-up window:
        val shouldExecute = hasWake || inConversationWindow || (currentState == VoiceState.LISTENING || currentState == VoiceState.WAKE_DETECTED)

        if (!shouldExecute) {
            // Speech recognized did NOT contain wake word -> False activation
            VoiceInstrumentation.onFalseActivation("Recognized text \"$text\" had no wake word", currentState.name)
            returnToIdleState("Speech did not contain wake word")
            return
        }

        if (hasWake) {
            VoiceInstrumentation.onWakeWordConfirmed("Jarvis", currentState.name)
        }

        // 3. Extract clean command (e.g. "Jarvis, take me home" -> "take me home")
        val cleanCommand = if (hasWake) WakeWordEngine.extractCommand(text) else text
        if (cleanCommand.isBlank()) {
            speakResponse("Yes? How can I help you?", openConversation = true)
            return
        }

        // 4. Execute via unified autonomous system (Deterministic Needle -> Local SLM -> Cloud Gemini LLM)
        updateState(VoiceState.PROCESSING, "\"$cleanCommand\"")
        Thread {
            com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.execute(applicationContext, cleanCommand) { res ->
                speakResponse(res.speechResponse, openConversation = VoiceAssistantPreferences.isConversationMode(applicationContext))
            }
        }.start()
    }

    private fun speakResponse(text: String, openConversation: Boolean) {
        mainHandler.post {
            // Lock out acoustic monitor / STT during SPEAKING to prevent self-TTS activation
            updateState(VoiceState.SPEAKING, text)
            stopAcousticDetector()

            voiceEngine?.speak(text, interrupt = true) {
                if (openConversation && VoiceAssistantPreferences.isConversationMode(applicationContext)) {
                    enterConversationWindow()
                } else {
                    returnToIdleState("Speech completed")
                }
            }
        }
    }

    private fun enterConversationWindow() {
        inConversationWindow = true
        conversationTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        val duration = VoiceAssistantPreferences.getFollowUpDurationSec(applicationContext) * 1000L

        conversationTimerRunnable = Runnable {
            returnToIdleState("Conversation window timed out")
        }
        mainHandler.postDelayed(conversationTimerRunnable!!, duration)

        // Start follow-up listening session
        startDeliberateListeningSession("Conversation follow-up")
    }

    private fun stopActiveSttSession(reason: String) {
        if (activeSttSessionRunning) {
            activeSttSessionRunning = false
            VoiceInstrumentation.onListenerStop(reason, currentState.name)
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}
        }
    }

    private fun shutdownService() {
        isRunning = false
        inConversationWindow = false
        activeSttSessionRunning = false
        conversationTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        backoffRunnable?.let { mainHandler.removeCallbacks(it) }
        stopActiveSttSession("Service shutdown")
        stopAcousticDetector()
        voiceEngine?.destroy()
        updateState(VoiceState.OFF, "Hands-Free service stopped")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        shutdownService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Hands-Free Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live status of JARVIS background voice assistant"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS Voice Assistant · ${currentState.name}")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
