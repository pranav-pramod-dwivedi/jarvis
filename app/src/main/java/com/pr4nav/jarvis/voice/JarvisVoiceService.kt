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
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pr4nav.jarvis.MainActivity
import com.pr4nav.jarvis.R
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JARVIS Core — The Resilient Always-Available Foreground Voice Service.
 *
 * Runs as an Android-compliant Foreground Service with type MICROPHONE.
 * Independent of Activities, WebViews, and ephemeral UI lifecycles.
 *
 * State Machine:
 * - OFF
 * - IDLE (quiet low-power candidate VAD)
 * - WAKE_DETECTED (verified wake word)
 * - STARTING_LISTENER
 * - LISTENING (active intentional SpeechRecognizer session)
 * - PROCESSING (unified routing & reasoning)
 * - SPEAKING (HD TTS playback with barge-in support)
 * - FOLLOW_UP_LISTENING (conversational multi-turn window)
 * - CALL_INTERRUPTED (telephony call in progress, mic and tts halted)
 * - RESUMING (telephony call finished, safely restoring idle state)
 * - PAUSED (user-requested standby)
 * - ERROR (controlled backoff)
 * - PERMISSION_REQUIRED (missing RECORD_AUDIO)
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
        CALL_INTERRUPTED,
        RESUMING,
        PAUSED,
        ERROR,
        PERMISSION_REQUIRED
    }

    interface CoreObserver {
        fun onStateChanged(state: VoiceState, detail: String)
        fun onSpeechRecognized(text: String)
        fun onResponseSynthesized(speechText: String, fullSummary: String)
        fun onThinkingTrace(trace: String)
    }

    companion object {
        private const val TAG = "JarvisVoiceService"
        private const val CHANNEL_ID = "jarvis_voice_core_channel"
        private const val NOTIFICATION_ID = 4040

        const val ACTION_START = "com.pr4nav.jarvis.voice.START"
        const val ACTION_STOP = "com.pr4nav.jarvis.voice.STOP"
        const val ACTION_PAUSE = "com.pr4nav.jarvis.voice.PAUSE"
        const val ACTION_RESUME = "com.pr4nav.jarvis.voice.RESUME"
        const val ACTION_STOP_SPEAKING = "com.pr4nav.jarvis.voice.STOP_SPEAKING"
        const val ACTION_START_ACTIVE_SESSION = "com.pr4nav.jarvis.voice.START_ACTIVE_SESSION"

        @Volatile var currentState: VoiceState = VoiceState.OFF
            private set
        @Volatile var currentDetail: String = "Initializing"
            private set
        @Volatile var isRunning: Boolean = false
            private set

        @Volatile private var instance: JarvisVoiceService? = null

        fun start(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start JarvisVoiceService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun pause(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_PAUSE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun resume(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_RESUME
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stopSpeaking(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_STOP_SPEAKING
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun triggerActiveSession(context: Context, reason: String = "User Trigger") {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_START_ACTIVE_SESSION
                putExtra("reason", reason)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger active session: ${e.message}", e)
            }
        }

        fun registerObserver(observer: CoreObserver) {
            instance?.registerObserver(observer)
        }

        fun unregisterObserver(observer: CoreObserver) {
            instance?.unregisterObserver(observer)
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceEngine: JarvisVoiceEngine? = null
    private var acousticDetector: AcousticWakeDetector? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<CoreObserver>()

    private var inConversationWindow = false
    private var conversationTimerRunnable: Runnable? = null
    private var backoffRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var activeSttSessionRunning = false
    private var errorRetryCount = 0

    private var executionWakeLock: PowerManager.WakeLock? = null
    private var audioCoordinator: JarvisAudioCoordinator? = null
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null
    private var legacyPhoneStateListener: PhoneStateListener? = null
    @Volatile var isCallActive: Boolean = false
        private set

    fun registerObserver(observer: CoreObserver) {
        if (!observers.contains(observer)) {
            observers.add(observer)
            mainHandler.post {
                observer.onStateChanged(currentState, currentDetail)
            }
        }
    }

    fun unregisterObserver(observer: CoreObserver) {
        observers.remove(observer)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        voiceEngine = JarvisVoiceEngine(applicationContext)
        audioCoordinator = JarvisAudioCoordinator(applicationContext).apply {
            startDeviceMonitoring()
        }
        registerTelephonyListener()
        createNotificationChannel()
        startGuardianWatchdog()

        // Android 14+ Compliant Foreground Service Start
        val notif = buildNotification("JARVIS Core Initializing")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasMic = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val fgsType = if (hasMic) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notif, fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasMic = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val fgsType = if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                if (fgsType != 0) {
                    startForeground(NOTIFICATION_ID, notif, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notif)
                }
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed during onCreate: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action: ${intent?.action}, flags: $flags, startId: $startId")

        // 1. Process Death / Service Recreation Check (intent == null)
        if (intent == null) {
            val handsFreeEnabled = VoiceAssistantPreferences.isHandsFreeEnabled(applicationContext)
            if (handsFreeEnabled) {
                Log.i(TAG, "Reconstructing JARVIS runtime state after process recreation")
                checkPermissionsAndInitialize()
            } else {
                Log.i(TAG, "Hands-free disabled in preferences; stopping recreated service")
                shutdownService()
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                shutdownService()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                updateState(VoiceState.PAUSED, "Paused by user")
                stopActiveSttSession("User paused")
                stopAcousticDetector()
                releaseExecutionWakeLock()
                return START_STICKY
            }
            ACTION_RESUME -> {
                returnToIdleState("User resumed")
                return START_STICKY
            }
            ACTION_STOP_SPEAKING -> {
                audioCoordinator?.abandonAssistantFocus()
                voiceEngine?.stopSpeaking()
                releaseExecutionWakeLock()
                returnToIdleState("Speech stopped by user")
                return START_STICKY
            }
            ACTION_START_ACTIVE_SESSION -> {
                val reason = intent.getStringExtra("reason") ?: "Intentional trigger"
                startDeliberateListeningSession(reason)
                return START_STICKY
            }
            ACTION_START -> {
                // Idempotent start protection: If already IDLE and actively listening, do not recreate pipeline
                if (currentState == VoiceState.IDLE && acousticDetector?.isListening() == true) {
                    Log.d(TAG, "Service already actively listening in IDLE state; ignoring duplicate start")
                    updateNotification()
                    return START_STICKY
                }
                checkPermissionsAndInitialize()
                return START_STICKY
            }
            else -> {
                checkPermissionsAndInitialize()
                return START_STICKY
            }
        }
    }

    private fun checkPermissionsAndInitialize() {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        Log.i(TAG, "checkPermissionsAndInitialize: hasMic=$hasMic")

        if (!hasMic) {
            updateState(VoiceState.PERMISSION_REQUIRED, "Microphone permission required")
            VoiceInstrumentation.log("INIT_BLOCKED", "Missing RECORD_AUDIO", currentState.name)
            return
        }

        returnToIdleState("Service ready")
    }

    private fun updateState(state: VoiceState, detail: String) {
        currentState = state
        currentDetail = detail
        Log.i(TAG, "State transition -> $state ($detail)")

        for (obs in observers) {
            try {
                obs.onStateChanged(state, detail)
            } catch (e: Exception) {
                Log.w(TAG, "Error notifying observer: ${e.message}")
            }
        }

        updateNotification()
    }

    private fun updateNotification() {
        try {
            val notif = buildNotification(currentDetail)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, notif)
        } catch (_: Exception) {}
    }

    /**
     * IDLE: SpeechRecognizer is NOT active.
     * Starts lightweight acoustic detector waiting for speech or wake trigger.
     */
    private fun returnToIdleState(reason: String) {
        mainHandler.post {
            if (!isRunning || currentState == VoiceState.PAUSED) return@post
            if (isCallActive || currentState == VoiceState.CALL_INTERRUPTED) {
                updateState(VoiceState.CALL_INTERRUPTED, "📞 Phone call in progress")
                return@post
            }

            stopActiveSttSession(reason)
            releaseExecutionWakeLock()
            inConversationWindow = false
            errorRetryCount = 0
            updateState(VoiceState.IDLE, "Listening for 'Hey Jarvis'")
            VoiceInstrumentation.log("ENTER_IDLE", reason, currentState.name)

            // Start low-power acoustic monitoring
            startAcousticDetector()
        }
    }

    private fun startAcousticDetector() {
        stopAcousticDetector()
        if (isCallActive || currentState == VoiceState.CALL_INTERRUPTED) {
            Log.i(TAG, "Skipping startAcousticDetector: phone call is active")
            return
        }
        val wakeEngine = WakeWordEngineManager.getActiveEngine(applicationContext)
        Log.i(TAG, "startAcousticDetector: wakeEngine=${wakeEngine.name}, isInstalled=${wakeEngine.isInstalled}")

        acousticDetector = AcousticWakeDetector(
            context = applicationContext,
            wakeWordEngine = wakeEngine,
            onWakeWordDetected = { wakeWord ->
                mainHandler.post {
                    if (isCallActive || currentState == VoiceState.CALL_INTERRUPTED) {
                        Log.i(TAG, "Wake word ignored: phone call active")
                        return@post
                    }
                    if (currentState == VoiceState.SPEAKING || voiceEngine?.isSpeaking() == true) {
                        Log.i(TAG, "Barge-in interrupt received: Stopping speech playback")
                        voiceEngine?.stopSpeaking()
                    }
                    if (currentState != VoiceState.PAUSED && currentState != VoiceState.OFF) {
                        VoiceInstrumentation.onWakeWordConfirmed(wakeWord, currentState.name)
                        updateState(VoiceState.WAKE_DETECTED, "Wake word confirmed: $wakeWord")

                        // Turn screen on and prepare display
                        wakeUpScreen()

                        // Trigger Floating HUD Overlay if permission granted
                        try {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(applicationContext)) {
                                com.pr4nav.jarvis.companion.JarvisOverlayService.showHud(applicationContext)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to launch overlay on wake word: ${e.message}")
                        }

                        // Respond immediately with a short, natural acknowledgement: "Yes?", "At your service.", "Hi!"
                        val acks = listOf("Yes?", "At your service.", "I'm listening.", "Hi!")
                        val ack = acks.random()
                        voiceEngine?.speak(ack, interrupt = true) {
                            mainHandler.post {
                                startDeliberateListeningSession("Wake word confirmed: $wakeWord")
                            }
                        } ?: run {
                            startDeliberateListeningSession("Wake word confirmed: $wakeWord")
                        }
                    } else {
                        returnToIdleState("Wake word received while inactive")
                    }
                }
            },
            onVoiceActivityCandidate = {
                VoiceInstrumentation.onVadEvent("Ambient voice candidate detected", currentState.name)
            },
            onDetectorDied = {
                mainHandler.post {
                    if (isRunning && currentState == VoiceState.IDLE) {
                        Log.w(TAG, "AcousticWakeDetector thread ended unexpectedly; auto-reviving in 600ms...")
                        mainHandler.postDelayed({
                            if (isRunning && currentState == VoiceState.IDLE) {
                                returnToIdleState("Acoustic detector revival")
                            }
                        }, 600L)
                    }
                }
            }
        )
        acquireAmbientWakeLock()
        acousticDetector?.start()
    }

    private fun stopAcousticDetector() {
        try {
            releaseAmbientWakeLock()
            acousticDetector?.stop()
            acousticDetector = null
        } catch (_: Exception) {}
    }

    /**
     * Starts ONE intentional SpeechRecognizer session with bounded WakeLock.
     * Does NOT loop indefinitely or restart on silence.
     */
    fun startDeliberateListeningSession(reason: String) {
        mainHandler.post {
            if (!isRunning || isCallActive || currentState == VoiceState.CALL_INTERRUPTED || currentState == VoiceState.PAUSED || currentState == VoiceState.OFF) return@post
            if (activeSttSessionRunning) {
                Log.d(TAG, "SpeechRecognizer already running, ignoring redundant start request")
                return@post
            }

            acquireExecutionWakeLock()
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

                        // Controlled backoff to IDLE rather than rapid infinite restart
                        handleSttErrorWithBackoff(error, errorMsg)
                    }

                    override fun onResults(results: Bundle?) {
                        activeSttSessionRunning = false
                        VoiceInstrumentation.onListenerStop("Results received", currentState.name)

                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim().orEmpty()

                        if (text.isNotBlank()) {
                            VoiceInstrumentation.onSuccess(text, currentState.name)
                            for (obs in observers) {
                                try { obs.onSpeechRecognized(text) } catch (_: Exception) {}
                            }
                            handleRecognizedText(text)
                        } else {
                            returnToIdleState("Empty recognition result")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
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
        releaseExecutionWakeLock()

        val backoffDelay = minOf(1500L * errorRetryCount, 5000L)
        backoffRunnable?.let { mainHandler.removeCallbacks(it) }
        backoffRunnable = Runnable {
            returnToIdleState("Backoff expired ($errorMsg)")
        }
        mainHandler.postDelayed(backoffRunnable!!, backoffDelay)
    }

    private fun handleRecognizedText(text: String) {
        Log.i(TAG, "Processing utterance: \"$text\"")

        // 1. Barge-in / stop check (STOP SPEAKING != CANCEL TASK)
        if (WakeWordEngine.isStopCommand(text)) {
            voiceEngine?.stopSpeaking()
            inConversationWindow = false
            returnToIdleState("User commanded stop")
            return
        }

        // 2. Wake-word check
        val hasWake = WakeWordEngine.containsWakeWord(text)
        val shouldExecute = hasWake || inConversationWindow ||
                (currentState == VoiceState.LISTENING || currentState == VoiceState.WAKE_DETECTED)

        if (!shouldExecute) {
            VoiceInstrumentation.onFalseActivation("Recognized text \"$text\" had no wake word", currentState.name)
            returnToIdleState("Speech did not contain wake word")
            return
        }

        if (hasWake) {
            VoiceInstrumentation.onWakeWordConfirmed("Jarvis", currentState.name)
        }

        // 3. Extract clean command
        val cleanCommand = if (hasWake) WakeWordEngine.extractCommand(text) else text
        if (cleanCommand.isBlank()) {
            speakResponse("Yes? How can I help you?", openConversation = true)
            return
        }

        // 4. Execute via unified autonomous system
        acquireExecutionWakeLock()
        updateState(VoiceState.PROCESSING, "\"$cleanCommand\"")

        val voiceSession = com.pr4nav.jarvis.session.JarvisSessionManager.getActiveSession(
            applicationContext,
            com.pr4nav.jarvis.session.SessionType.VOICE_CHAT
        )
        com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
            applicationContext,
            voiceSession,
            com.pr4nav.jarvis.session.SessionMessage(sender = "user", text = cleanCommand)
        )

        val execSafetyRunnable = Runnable {
            Log.w(TAG, "Command execution safety timeout (40s); auto-recovering to IDLE")
            returnToIdleState("Command execution timed out")
        }
        mainHandler.postDelayed(execSafetyRunnable, 40_000L)

        Thread {
            try {
                com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.execute(
                    context = applicationContext,
                    rawQuery = cleanCommand,
                    onStatus = { status ->
                        for (obs in observers) {
                            try { obs.onThinkingTrace(status) } catch (_: Exception) {}
                        }
                    },
                    onChunk = null,
                    onResult = { res ->
                        mainHandler.removeCallbacks(execSafetyRunnable)
                        com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                            applicationContext,
                            voiceSession,
                            com.pr4nav.jarvis.session.SessionMessage(
                                sender = "agent",
                                text = "${res.source.badge}\n${res.jarvisResponse.text}",
                                steps = listOf("Model: ${res.modelName}", "Thinking: ${res.thinkingTrace}"),
                                isSuccess = res.handled
                            )
                        )

                        for (obs in observers) {
                            try {
                                obs.onResponseSynthesized(res.jarvisResponse.speechText, res.jarvisResponse.text)
                                obs.onThinkingTrace(res.thinkingTrace)
                            } catch (_: Exception) {}
                        }

                        speakResponse(
                            res.jarvisResponse.speechText,
                            openConversation = VoiceAssistantPreferences.isConversationMode(applicationContext)
                        )
                    }
                )
            } catch (e: Exception) {
                mainHandler.removeCallbacks(execSafetyRunnable)
                Log.e(TAG, "Execution exception: ${e.message}", e)
                speakResponse("An internal error occurred while processing your request.", openConversation = false)
            }
        }.start()
    }

    private fun speakResponse(text: String, openConversation: Boolean) {
        mainHandler.post {
            updateState(VoiceState.SPEAKING, text)
            stopAcousticDetector()

            val safetyMs = maxOf(6000L, text.length * 85L) + 4000L
            val speechSafetyRunnable = Runnable {
                Log.w(TAG, "TTS speech safety timeout fired after ${safetyMs}ms; resetting to IDLE")
                audioCoordinator?.abandonAssistantFocus()
                voiceEngine?.stopSpeaking()
                returnToIdleState("TTS safety timeout")
            }
            mainHandler.postDelayed(speechSafetyRunnable, safetyMs)

            try {
                audioCoordinator?.requestAssistantFocus {
                    voiceEngine?.stopSpeaking()
                }
                voiceEngine?.speak(text, interrupt = true) {
                    mainHandler.post {
                        audioCoordinator?.abandonAssistantFocus()
                        mainHandler.removeCallbacks(speechSafetyRunnable)
                        if (openConversation && VoiceAssistantPreferences.isConversationMode(applicationContext)) {
                            enterConversationWindow()
                        } else {
                            returnToIdleState("Speech completed")
                        }
                    }
                } ?: run {
                    audioCoordinator?.abandonAssistantFocus()
                    mainHandler.removeCallbacks(speechSafetyRunnable)
                    returnToIdleState("VoiceEngine null")
                }
            } catch (e: Exception) {
                audioCoordinator?.abandonAssistantFocus()
                mainHandler.removeCallbacks(speechSafetyRunnable)
                Log.e(TAG, "TTS speak exception: ${e.message}", e)
                returnToIdleState("TTS error fallback")
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

    private fun acquireExecutionWakeLock() {
        try {
            if (executionWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                executionWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:command_execution")
            }
            if (executionWakeLock?.isHeld != true) {
                executionWakeLock?.acquire(30_000L) // 30s safety timeout
                Log.d(TAG, "Acquired execution WakeLock (30s max)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire error: ${e.message}")
        }
    }

    private fun releaseExecutionWakeLock() {
        try {
            if (executionWakeLock?.isHeld == true) {
                executionWakeLock?.release()
                Log.d(TAG, "Released execution WakeLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release error: ${e.message}")
        }
    }

    private var ambientListenWakeLock: PowerManager.WakeLock? = null

    private fun acquireAmbientWakeLock() {
        try {
            if (ambientListenWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                ambientListenWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:ambient_acoustic")
            }
            if (ambientListenWakeLock?.isHeld != true) {
                ambientListenWakeLock?.acquire()
                Log.d(TAG, "Acquired ambient acoustic WakeLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ambient WakeLock acquire error: ${e.message}")
        }
    }

    private fun releaseAmbientWakeLock() {
        try {
            if (ambientListenWakeLock?.isHeld == true) {
                ambientListenWakeLock?.release()
                Log.d(TAG, "Released ambient acoustic WakeLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ambient WakeLock release error: ${e.message}")
        }
    }

    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                    "jarvis:screen_wake_on_lock"
                )
                wl.acquire(10_000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screen wakeLock error: ${e.message}")
        }

        try {
            com.pr4nav.jarvis.Shell.root("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
        } catch (_: Exception) {}
    }

    private fun registerTelephonyListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            telephonyManager = tm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallStateChange(state)
                    }
                }
                telephonyCallback = cb
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb)
                Log.i(TAG, "Registered TelephonyCallback.CallStateListener (API 31+)")
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallStateChange(state)
                    }
                }
                legacyPhoneStateListener = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                Log.i(TAG, "Registered legacy PhoneStateListener")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register telephony listener: ${e.message}")
        }
    }

    private fun unregisterTelephonyListener() {
        try {
            val tm = telephonyManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                legacyPhoneStateListener?.let {
                    @Suppress("DEPRECATION")
                    tm.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {}
        telephonyCallback = null
        legacyPhoneStateListener = null
        telephonyManager = null
    }

    fun handleCallStateChange(state: Int) {
        mainHandler.post {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    Log.i(TAG, "Telephony state became ACTIVE ($state) -> Interrupting JARVIS voice")
                    isCallActive = true
                    if (currentState != VoiceState.CALL_INTERRUPTED) {
                        updateState(VoiceState.CALL_INTERRUPTED, "📞 Phone call in progress")
                        stopActiveSttSession("Phone call started")
                        voiceEngine?.stopSpeaking()
                        stopAcousticDetector()
                        inConversationWindow = false
                        conversationTimerRunnable?.let { mainHandler.removeCallbacks(it) }
                        releaseExecutionWakeLock()
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (isCallActive) {
                        Log.i(TAG, "Telephony state became IDLE -> Safely resuming JARVIS voice in 1200ms")
                        isCallActive = false
                        if (currentState == VoiceState.CALL_INTERRUPTED) {
                            updateState(VoiceState.RESUMING, "📞 Call ended — restoring voice")
                            mainHandler.postDelayed({
                                if (isRunning && !isCallActive && (currentState == VoiceState.RESUMING || currentState == VoiceState.CALL_INTERRUPTED)) {
                                    returnToIdleState("Call ended")
                                }
                            }, 1200L)
                        }
                    }
                }
            }
        }
    }

    private fun shutdownService() {
        isRunning = false
        inConversationWindow = false
        activeSttSessionRunning = false
        stopGuardianWatchdog()
        unregisterTelephonyListener()
        audioCoordinator?.release()
        audioCoordinator = null
        conversationTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        backoffRunnable?.let { mainHandler.removeCallbacks(it) }
        stopActiveSttSession("Service shutdown")
        stopAcousticDetector()
        releaseExecutionWakeLock()
        voiceEngine?.destroy()
        updateState(VoiceState.OFF, "JARVIS Core stopped")
        observers.clear()
        instance = null
        try {
            stopForeground(true)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun startGuardianWatchdog() {
        stopGuardianWatchdog()
        watchdogRunnable = object : Runnable {
            override fun run() {
                try {
                    val handsFree = VoiceAssistantPreferences.isHandsFreeEnabled(applicationContext)
                    if (isRunning && handsFree && !isCallActive && currentState != VoiceState.CALL_INTERRUPTED) {
                        if (currentState == VoiceState.IDLE) {
                            if (acousticDetector == null || acousticDetector?.isListening() != true) {
                                Log.w(TAG, "GuardianWatchdog: Acoustic detector inactive while IDLE! Reviving...")
                                startAcousticDetector()
                            }
                        } else if (currentState == VoiceState.ERROR) {
                            Log.w(TAG, "GuardianWatchdog: Service in ERROR state! Auto-recovering to IDLE...")
                            returnToIdleState("Watchdog ERROR auto-recovery")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GuardianWatchdog loop error: ${e.message}")
                }
                mainHandler.postDelayed(this, 5000L)
            }
        }
        mainHandler.postDelayed(watchdogRunnable!!, 5000L)
    }

    private fun stopGuardianWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
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
                "JARVIS Core Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live status and controls for JARVIS core voice service"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // Action: Pause / Resume
        val toggleActionIntent = Intent(this, JarvisVoiceService::class.java).apply {
            action = if (currentState == VoiceState.PAUSED) ACTION_RESUME else ACTION_PAUSE
        }
        val togglePending = PendingIntent.getService(
            this, 1, toggleActionIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val toggleTitle = if (currentState == VoiceState.PAUSED) "Resume" else "Pause"

        // Action: Stop
        val stopActionIntent = Intent(this, JarvisVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopActionIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS Core · ${currentState.name}")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, toggleTitle, togglePending)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .build()
    }
}
