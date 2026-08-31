package com.pr4nav.jarvis.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.pr4nav.jarvis.AgentActivity
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.capabilities.ScreenContextReader
import com.pr4nav.jarvis.gui.StarkWaveformView
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import com.pr4nav.jarvis.voice.JarvisVoiceEngine
import com.pr4nav.jarvis.voice.VoiceAssistantPreferences

/**
 * Futuristic Siri-Style & Stark Holographic Floating Overlay HUD.
 *
 * Runs as a floating interactive window over any application (Chrome, YouTube, Games, Home Screen)
 * giving the user instant AI capabilities, live animated waveform feedback, and hands-free voice control.
 */
class JarvisOverlayService : Service() {

    companion object {
        private const val TAG = "JarvisOverlayService"
        private const val NOTIFICATION_ID = 4040
        private const val CHANNEL_ID = "jarvis_overlay_channel"

        const val ACTION_SHOW_HUD = "com.pr4nav.jarvis.overlay.SHOW_HUD"
        const val ACTION_HIDE_HUD = "com.pr4nav.jarvis.overlay.HIDE_HUD"
        const val ACTION_TOGGLE_BUBBLE = "com.pr4nav.jarvis.overlay.TOGGLE_BUBBLE"
        const val ACTION_TRIGGER_VOICE = "com.pr4nav.jarvis.overlay.TRIGGER_VOICE"

        @Volatile var isRunning = false
            private set

        fun showHud(context: Context) {
            val intent = Intent(context, JarvisOverlayService::class.java).apply {
                action = ACTION_SHOW_HUD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hideHud(context: Context) {
            val intent = Intent(context, JarvisOverlayService::class.java).apply {
                action = ACTION_HIDE_HUD
            }
            context.startService(intent)
        }

        fun triggerVoice(context: Context) {
            val intent = Intent(context, JarvisOverlayService::class.java).apply {
                action = ACTION_TRIGGER_VOICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var hudCard: View? = null
    private var floatingBubble: View? = null
    private var waveformView: StarkWaveformView? = null
    private var txtStatusBadge: TextView? = null
    private var txtTranscription: TextView? = null
    private var txtResponse: TextView? = null

    private var voiceEngine: JarvisVoiceEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isExpanded = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        voiceEngine = JarvisVoiceEngine(applicationContext)
        createNotificationChannel()

        val notif = buildNotification("JARVIS Companion Overlay Active")
        startForeground(NOTIFICATION_ID, notif)

        initOverlayView()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HUD companion overlay over other applications"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS HUD Overlay")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun initOverlayView() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_Jarvis)
        val inflater = LayoutInflater.from(themedContext)
        overlayView = inflater.inflate(R.layout.layout_jarvis_floating_hud, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 50
        }

        setupViews()
        try {
            windowManager?.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding overlay view: ${e.message}", e)
        }
    }

    private fun setupViews() {
        val root = overlayView ?: return
        hudCard = root.findViewById(R.id.hud_card)
        floatingBubble = root.findViewById(R.id.hud_floating_bubble)
        waveformView = root.findViewById(R.id.hud_waveform)
        txtStatusBadge = root.findViewById(R.id.hud_status_badge)
        txtTranscription = root.findViewById(R.id.hud_transcription)
        txtResponse = root.findViewById(R.id.hud_response)

        // Close button
        root.findViewById<View>(R.id.btn_hud_close)?.setOnClickListener {
            hideHudView()
        }

        // Minimize to bubble
        root.findViewById<View>(R.id.btn_hud_minimize)?.setOnClickListener {
            minimizeToBubble()
        }

        // Expand bubble
        floatingBubble?.setOnClickListener {
            expandToHud()
        }

        // Touch listener for dragging the floating bubble
        setupBubbleDragger()

        // Quick action chips
        root.findViewById<View>(R.id.chip_hud_stop)?.setOnClickListener {
            stopAssistantSpeech()
        }

        root.findViewById<View>(R.id.chip_hud_mic)?.setOnClickListener {
            startOverlayVoiceFlow()
        }

        root.findViewById<View>(R.id.chip_hud_screen)?.setOnClickListener {
            readAndSummarizeScreen()
        }

        root.findViewById<View>(R.id.chip_hud_chat)?.setOnClickListener {
            openMainChat()
        }
    }

    private fun setupBubbleDragger() {
        val bubble = floatingBubble ?: return
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubble.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initialX + (event.rawX - initialTouchX).toInt()
                    lp.y = initialY - (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        expandToHud()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun minimizeToBubble() {
        isExpanded = false
        hudCard?.visibility = View.GONE
        floatingBubble?.visibility = View.VISIBLE
        layoutParams?.let { lp ->
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            lp.x = 20
            windowManager?.updateViewLayout(overlayView, lp)
        }
    }

    private fun expandToHud() {
        isExpanded = true
        floatingBubble?.visibility = View.GONE
        hudCard?.visibility = View.VISIBLE
        layoutParams?.let { lp ->
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.x = 0
            lp.y = 50
            windowManager?.updateViewLayout(overlayView, lp)
        }
        startOverlayVoiceFlow()
    }

    fun startOverlayVoiceFlow() {
        if (!isExpanded) expandToHud()

        voiceEngine?.stopSpeaking()
        txtStatusBadge?.text = "LISTENING"
        txtStatusBadge?.setTextColor(Color.parseColor("#00E5FF"))
        txtTranscription?.text = "Listening to voice command…"
        txtResponse?.visibility = View.GONE
        waveformView?.setActive(true)
        waveformView?.setAmplitude(600f)

        // Run recognition loop
        voiceEngine?.startListening(
            activity = null,
            onPartial = { partial ->
                mainHandler.post {
                    txtTranscription?.text = partial
                    waveformView?.setAmplitude(1200f)
                }
            },
            onResult = { result ->
                mainHandler.post {
                    txtTranscription?.text = "\"$result\""
                    txtStatusBadge?.text = "THINKING"
                    txtStatusBadge?.setTextColor(Color.parseColor("#F59E0B"))
                    waveformView?.setAmplitude(800f)
                    executeHudCommand(result)
                }
            },
            onError = { err ->
                mainHandler.post {
                    txtStatusBadge?.text = "IDLE"
                    txtStatusBadge?.setTextColor(Color.parseColor("#94A3B8"))
                    txtTranscription?.text = "Tap 'Listen Again' or speak 'Hey Jarvis'"
                    waveformView?.setActive(false)
                }
            }
        )
    }

    private fun executeHudCommand(command: String) {
        // Check for instant stop command
        if (com.pr4nav.jarvis.voice.WakeWordEngine.isStopCommand(command)) {
            stopAssistantSpeech()
            return
        }

        val voiceSession = com.pr4nav.jarvis.session.JarvisSessionManager.getActiveSession(
            applicationContext,
            com.pr4nav.jarvis.session.SessionType.VOICE_CHAT
        )
        com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
            applicationContext,
            voiceSession,
            com.pr4nav.jarvis.session.SessionMessage(sender = "user", text = command)
        )

        UnifiedAssistantDispatcher.execute(applicationContext, command) { res ->
            com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                applicationContext,
                voiceSession,
                com.pr4nav.jarvis.session.SessionMessage(
                    sender = "agent",
                    text = "${res.source.badge}\n${res.speechResponse}",
                    steps = listOf("Model: ${res.modelName}", "Trace: ${res.thinkingTrace}"),
                    isSuccess = res.handled
                )
            )

            mainHandler.post {
                txtStatusBadge?.text = res.modelName.uppercase()
                txtStatusBadge?.setTextColor(
                    when (res.source) {
                        com.pr4nav.jarvis.router.ExecutionSource.AGY_AGENT -> Color.parseColor("#38BDF8")
                        com.pr4nav.jarvis.router.ExecutionSource.CLOUD_LLM -> Color.parseColor("#60A5FA")
                        com.pr4nav.jarvis.router.ExecutionSource.LOCAL_LLM -> Color.parseColor("#10B981")
                        com.pr4nav.jarvis.router.ExecutionSource.DETERMINISTIC_NEEDLE -> Color.parseColor("#F59E0B")
                        com.pr4nav.jarvis.router.ExecutionSource.FALLBACK -> Color.parseColor("#94A3B8")
                    }
                )
                txtResponse?.visibility = View.VISIBLE
                txtResponse?.text = "${res.source.badge}\n${res.speechResponse}"
                waveformView?.setAmplitude(1500f)

                // Speak response via Kokoro-82M TTS (only speak the natural clean speechResponse)
                voiceEngine?.speak(res.speechResponse, interrupt = true) {
                    mainHandler.post {
                        txtStatusBadge?.text = "READY"
                        txtStatusBadge?.setTextColor(Color.parseColor("#94A3B8"))
                        waveformView?.setActive(false)
                    }
                }
            }
        }
    }

    private fun readAndSummarizeScreen() {
        txtStatusBadge?.text = "ANALYZING SCREEN"
        txtStatusBadge?.setTextColor(Color.parseColor("#38BDF8"))
        txtTranscription?.text = "Inspecting on-screen content…"
        waveformView?.setActive(true)

        val voiceSession = com.pr4nav.jarvis.session.JarvisSessionManager.getActiveSession(
            applicationContext,
            com.pr4nav.jarvis.session.SessionType.VOICE_CHAT
        )
        com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
            applicationContext,
            voiceSession,
            com.pr4nav.jarvis.session.SessionMessage(sender = "user", text = "Summarize screen content")
        )

        ScreenContextReader.analyzeAndSummarizeScreen(applicationContext) { summary ->
            com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                applicationContext,
                voiceSession,
                com.pr4nav.jarvis.session.SessionMessage(
                    sender = "agent",
                    text = summary,
                    steps = listOf("Accessibility Screen Analysis"),
                    isSuccess = true
                )
            )

            mainHandler.post {
                txtStatusBadge?.text = "SPEAKING"
                txtStatusBadge?.setTextColor(Color.parseColor("#10B981"))
                txtResponse?.visibility = View.VISIBLE
                txtResponse?.text = summary
                waveformView?.setAmplitude(1400f)

                voiceEngine?.speak(summary, interrupt = true) {
                    mainHandler.post {
                        txtStatusBadge?.text = "READY"
                        txtStatusBadge?.setTextColor(Color.parseColor("#94A3B8"))
                        waveformView?.setActive(false)
                    }
                }
            }
        }
    }

    private fun stopAssistantSpeech() {
        voiceEngine?.stopSpeaking()
        txtStatusBadge?.text = "STOPPED"
        txtStatusBadge?.setTextColor(Color.parseColor("#EF4444"))
        waveformView?.setActive(false)
    }

    private fun openMainChat() {
        try {
            val intent = Intent(this, AgentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            minimizeToBubble()
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching chat: ${e.message}")
        }
    }

    private fun hideHudView() {
        stopAssistantSpeech()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_HUD -> {
                expandToHud()
            }
            ACTION_HIDE_HUD -> {
                hideHudView()
            }
            ACTION_TOGGLE_BUBBLE -> {
                if (isExpanded) minimizeToBubble() else expandToHud()
            }
            ACTION_TRIGGER_VOICE -> {
                startOverlayVoiceFlow()
            }
            else -> {
                if (isExpanded) startOverlayVoiceFlow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopAssistantSpeech()
        voiceEngine?.destroy()
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (_: Exception) {}
    }
}
