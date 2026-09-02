package com.pr4nav.jarvis.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pr4nav.jarvis.AgentActivity
import com.pr4nav.jarvis.MainActivity
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.capabilities.ScreenContextReader
import com.pr4nav.jarvis.gui.StarkWaveformView
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import com.pr4nav.jarvis.session.SessionMessage
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
        private const val NOTIFICATION_ID = 4041
        private const val CHANNEL_ID = "jarvis_overlay_channel"

        const val ACTION_SHOW_HUD = "com.pr4nav.jarvis.overlay.SHOW_HUD"
        const val ACTION_HIDE_HUD = "com.pr4nav.jarvis.overlay.HIDE_HUD"
        const val ACTION_TOGGLE_BUBBLE = "com.pr4nav.jarvis.overlay.TOGGLE_BUBBLE"
        const val ACTION_TRIGGER_VOICE = "com.pr4nav.jarvis.overlay.TRIGGER_VOICE"
        const val ACTION_EXECUTE_COMMAND = "com.pr4nav.jarvis.overlay.EXECUTE_COMMAND"

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

    private var hudRoot: View? = null
    private var hudBgImage: View? = null
    private var hudHeader: View? = null
    private var hudCard: View? = null
    private var floatingBubble: View? = null
    private var hudTextureView: TextureView? = null
    private var hudMediaPlayer: MediaPlayer? = null
    private var hudSubtitle: TextView? = null
    private var hudMainText: TextView? = null
    private var hudOrbContainer: View? = null
    private var hudChatRecycler: RecyclerView? = null
    private val hudChatAdapter = HudChatAdapter()
    private var isStreamingUserStt = false
    private var currentSttMsgId = ""
    private var isMinimizedFromActiveSession = false
    private var btnHudMinimize: View? = null
    private var btnHudMaximize: View? = null
    private var btnHudClose: View? = null

    private var voiceEngine: JarvisVoiceEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isExpanded = true
    private var isListening = false

    private val coreObserver = object : com.pr4nav.jarvis.voice.JarvisVoiceService.CoreObserver {
        override fun onStateChanged(state: com.pr4nav.jarvis.voice.JarvisVoiceService.VoiceState, detail: String) {
            mainHandler.post {
                when (state) {
                    com.pr4nav.jarvis.voice.JarvisVoiceService.VoiceState.WAKE_DETECTED,
                    com.pr4nav.jarvis.voice.JarvisVoiceService.VoiceState.STARTING_LISTENER,
                    com.pr4nav.jarvis.voice.JarvisVoiceService.VoiceState.LISTENING -> {
                        hudSubtitle?.text = "Listening"
                    }
                    com.pr4nav.jarvis.voice.JarvisVoiceService.VoiceState.PROCESSING -> {
                        hudSubtitle?.text = "Thinking..."
                    }
                    com.pr4nav.jarvis.voice.JarvisVoiceService.VoiceState.SPEAKING -> {
                        hudSubtitle?.text = "Jarvis"
                    }
                    com.pr4nav.jarvis.voice.JarvisVoiceService.VoiceState.IDLE -> {
                        hudSubtitle?.text = "Listening"
                    }
                    else -> {}
                }
            }
        }

        override fun onSpeechRecognized(text: String) {
            mainHandler.post {
                hudSubtitle?.text = "Thinking..."
                setChatActive(true)
                val userMsg = SessionMessage(sender = "user", text = text)
                hudChatAdapter.addMessage(userMsg)
                hudChatRecycler?.smoothScrollToPosition(hudChatAdapter.itemCount - 1)
            }
        }

        override fun onResponseSynthesized(speechText: String, fullSummary: String) {
            mainHandler.post {
                hudSubtitle?.text = "Jarvis"
                setChatActive(true)
                val display = if (speechText.isNotBlank()) speechText else fullSummary
                val agentMsg = SessionMessage(
                    id = "agent_${System.currentTimeMillis()}",
                    sender = "agent",
                    text = display,
                    isSuccess = true
                )
                hudChatAdapter.addMessage(agentMsg)
                hudChatRecycler?.smoothScrollToPosition(hudChatAdapter.itemCount - 1)
            }
        }

        override fun onThinkingTrace(trace: String) {
            mainHandler.post {
                if (trace.isNotBlank()) {
                    hudSubtitle?.text = "Thinking..."
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        voiceEngine = JarvisVoiceEngine(applicationContext)
        com.pr4nav.jarvis.voice.JarvisVoiceService.registerObserver(coreObserver)
        createNotificationChannel()

        val notif = buildNotification("JARVIS Companion Overlay Active")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val fgsType = if (hasMic) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notif, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service start exception: ${e.message}")
        }

        initOverlayView()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Overlay HUD",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating HUD and interactive companion"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_IMMUTABLE
            else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS HUD Overlay")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
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

        val displayMetrics = resources.displayMetrics
        val halfScreenHeight = (displayMetrics.heightPixels * 0.52).toInt()

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            halfScreenHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            x = 0
            y = 0
        }

        setupViews()
        try {
            windowManager?.addView(overlayView, layoutParams)
            startOverlayVoiceFlow()
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding overlay view: ${e.message}", e)
        }
    }

    private fun setupViews() {
        val root = overlayView ?: return
        hudRoot = root.findViewById(R.id.hud_root)
        val radius = 36 * resources.displayMetrics.density
        hudRoot?.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
            }
        }
        hudRoot?.clipToOutline = true

        hudBgImage = root.findViewById(R.id.hud_bg_image)
        hudHeader = root.findViewById(R.id.hud_header)
        hudCard = root.findViewById(R.id.hud_card)
        floatingBubble = root.findViewById(R.id.hud_floating_bubble)
        hudTextureView = root.findViewById(R.id.hud_video_view)
        hudSubtitle = root.findViewById(R.id.hud_subtitle)
        hudMainText = root.findViewById(R.id.hud_main_text)
        hudOrbContainer = root.findViewById(R.id.hud_orb_container)
        hudChatRecycler = root.findViewById(R.id.hud_chat_recycler)
        hudChatRecycler?.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        hudChatRecycler?.adapter = hudChatAdapter

        // Fresh session every time HUD is created
        com.pr4nav.jarvis.session.JarvisSessionManager.createSession(
            applicationContext,
            com.pr4nav.jarvis.session.SessionType.VOICE_CHAT
        )
        hudChatAdapter.clear()
        setChatActive(false)

        btnHudMinimize = root.findViewById(R.id.btn_hud_minimize)
        btnHudMaximize = root.findViewById(R.id.btn_hud_maximize)
        btnHudClose = root.findViewById(R.id.btn_hud_close)

        setupHudVideoPlayer()

        // 1. Minimize button (-) -> collapse into floating bubble
        btnHudMinimize?.setOnClickListener {
            minimizeToBubble()
        }

        // 2. Maximize button (Box) -> open same active voice session directly in main app
        btnHudMaximize?.setOnClickListener {
            val activeSession = com.pr4nav.jarvis.session.JarvisSessionManager.getActiveSession(
                applicationContext,
                com.pr4nav.jarvis.session.SessionType.VOICE_CHAT
            )
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("open_session_id", activeSession.id)
            }
            startActivity(intent)
            hideHudView()
        }

        // 3. Close button (Cross) -> hide overlay HUD
        btnHudClose?.setOnClickListener {
            hideHudView()
        }

        // TAPPED ANYWHERE ELSE SHOULD MINIMISE IT, NOT CLOSE THAT SESSION!
        root.setOnClickListener {
            minimizeToBubble()
        }
        hudCard?.setOnClickListener {
            // Consume tap inside the card
        }
        hudOrbContainer?.setOnClickListener {
            onPillClicked()
        }

        // Expand bubble (preserving session!)
        floatingBubble?.setOnClickListener {
            expandToHud(preserveSession = true)
        }


        setupBubbleDragger()
    }

    private fun onPillClicked() {
        hudSubtitle?.text = "Listening"
        hudSubtitle?.setTextColor(Color.parseColor("#99FFFFFF"))
        if (com.pr4nav.jarvis.voice.JarvisVoiceService.isRunning) {
            com.pr4nav.jarvis.voice.JarvisVoiceService.triggerActiveSession(applicationContext, "Tapped HUD")
        } else {
            startOverlayVoiceFlow()
        }
    }

    private fun setupHudVideoPlayer() {
        val tv = hudTextureView ?: return
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                adjustTextureViewAspect(width, height)
                playHudVideo(surface)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                adjustTextureViewAspect(width, height)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releaseHudVideo()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        if (tv.isAvailable) {
            adjustTextureViewAspect(tv.width, tv.height)
            tv.surfaceTexture?.let { playHudVideo(it) }
        }
    }

    private fun adjustTextureViewAspect(width: Int, height: Int) {
        val tv = hudTextureView ?: return
        if (width <= 0 || height <= 0) return
        val matrix = android.graphics.Matrix()
        val minSide = Math.min(width, height)
        val sx = minSide.toFloat() / width
        val sy = minSide.toFloat() / height
        matrix.setScale(sx, sy, width / 2f, height / 2f)
        tv.setTransform(matrix)
    }

    private fun playHudVideo(surfaceTexture: SurfaceTexture) {
        try {
            if (hudMediaPlayer == null) {
                hudMediaPlayer = MediaPlayer()
            } else {
                hudMediaPlayer?.reset()
            }
            hudMediaPlayer?.setSurface(Surface(surfaceTexture))
            val afd = assets.openFd("voice/apple_siri_orb.mp4")
            hudMediaPlayer?.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            hudMediaPlayer?.isLooping = true
            hudMediaPlayer?.setVolume(0f, 0f)
            hudMediaPlayer?.prepareAsync()
            hudMediaPlayer?.setOnPreparedListener { mp ->
                mp.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error playing Apple Siri orb video: ${e.message}")
        }
    }

    private fun releaseHudVideo() {
        try {
            hudMediaPlayer?.stop()
            hudMediaPlayer?.release()
            hudMediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing HUD video: ${e.message}")
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
        hudHeader?.visibility = View.GONE
        hudBgImage?.visibility = View.GONE
        hudRoot?.clipToOutline = false
        hudRoot?.setBackgroundColor(Color.TRANSPARENT)
        floatingBubble?.visibility = View.VISIBLE
        layoutParams?.let { lp ->
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            lp.x = 20
            lp.y = 0
            windowManager?.updateViewLayout(overlayView, lp)
        }
    }

    private fun setChatActive(hasChats: Boolean) {
        if (hasChats) {
            hudMainText?.visibility = View.GONE
            hudChatRecycler?.visibility = View.VISIBLE
            updateOrbSize(true)
        } else {
            hudMainText?.visibility = View.VISIBLE
            hudChatRecycler?.visibility = View.GONE
            updateOrbSize(false)
        }
    }

    private fun updateOrbSize(hasChats: Boolean) {
        val lp = hudOrbContainer?.layoutParams ?: return
        val targetDp = if (hasChats) 130 else 230
        val targetPx = (targetDp * resources.displayMetrics.density).toInt()
        if (lp.height != targetPx) {
            lp.width = targetPx
            lp.height = targetPx
            hudOrbContainer?.layoutParams = lp
        }
    }

    private fun expandToHud(preserveSession: Boolean = false) {
        isExpanded = true
        floatingBubble?.visibility = View.GONE
        hudCard?.visibility = View.VISIBLE
        hudHeader?.visibility = View.VISIBLE
        hudBgImage?.visibility = View.VISIBLE
        hudRoot?.setBackgroundResource(R.drawable.bg_overlay_half_sheet)
        val radius = 36 * resources.displayMetrics.density
        hudRoot?.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
            }
        }
        hudRoot?.clipToOutline = true

        val displayMetrics = resources.displayMetrics
        val halfScreenHeight = (displayMetrics.heightPixels * 0.54).toInt()
        layoutParams?.let { lp ->
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = halfScreenHeight
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.x = 0
            lp.y = 0
            windowManager?.updateViewLayout(overlayView, lp)
        }
        hudSubtitle?.text = "Listening"
        hudSubtitle?.setTextColor(Color.parseColor("#99FFFFFF"))

        if (!preserveSession) {
            // New session every time HUD is freshly opened!
            com.pr4nav.jarvis.session.JarvisSessionManager.createSession(
                applicationContext,
                com.pr4nav.jarvis.session.SessionType.VOICE_CHAT
            )
            hudChatAdapter.clear()
            setChatActive(false)
        } else {
            // Reopening minimized bubble -> keep session!
            val activeSession = com.pr4nav.jarvis.session.JarvisSessionManager.getActiveSession(
                applicationContext,
                com.pr4nav.jarvis.session.SessionType.VOICE_CHAT
            )
            if (activeSession.messages.isNotEmpty()) {
                hudChatAdapter.setMessages(activeSession.messages)
                setChatActive(true)
                hudChatRecycler?.scrollToPosition(activeSession.messages.size - 1)
            } else {
                setChatActive(false)
            }
        }

        startOverlayVoiceFlow()
    }

    fun startOverlayVoiceFlow() {
        if (!isExpanded) return
        if (isListening) return
        isListening = true

        voiceEngine?.stopSpeaking()
        hudSubtitle?.text = "Listening"
        hudSubtitle?.setTextColor(Color.parseColor("#99FFFFFF"))

        // Run recognition loop (continuous always-listening) with Live STT
        voiceEngine?.startListening(
            activity = null,
            onPartial = { partial ->
                mainHandler.post {
                    hudSubtitle?.text = "Listening"
                    if (partial.isNotBlank()) {
                        setChatActive(true)
                        if (isStreamingUserStt) {
                            hudChatAdapter.updateLastMessage(
                                SessionMessage(id = currentSttMsgId, sender = "user", text = partial)
                            )
                        } else {
                            isStreamingUserStt = true
                            currentSttMsgId = "stt_${System.currentTimeMillis()}"
                            hudChatAdapter.addMessage(
                                SessionMessage(id = currentSttMsgId, sender = "user", text = partial)
                            )
                        }
                        hudChatRecycler?.smoothScrollToPosition(hudChatAdapter.itemCount - 1)
                    }
                }
            },
            onResult = { result ->
                isListening = false
                mainHandler.post {
                    hudSubtitle?.text = "Thinking..."
                    if (result.isNotBlank()) {
                        setChatActive(true)
                        val finalUserMsg = SessionMessage(
                            id = if (isStreamingUserStt) currentSttMsgId else "stt_${System.currentTimeMillis()}",
                            sender = "user",
                            text = result
                        )
                        if (isStreamingUserStt) {
                            hudChatAdapter.updateLastMessage(finalUserMsg)
                        } else {
                            hudChatAdapter.addMessage(finalUserMsg)
                        }
                        isStreamingUserStt = false
                        hudChatRecycler?.smoothScrollToPosition(hudChatAdapter.itemCount - 1)
                        executeHudCommand(result)
                    } else {
                        isStreamingUserStt = false
                        startOverlayVoiceFlow()
                    }
                }
            },
            onError = { _ ->
                isListening = false
                isStreamingUserStt = false
                mainHandler.postDelayed({
                    if (isExpanded && !isListening) {
                        hudSubtitle?.text = "Listening"
                        startOverlayVoiceFlow() // Resume listening silently while overlay is open
                    }
                }, 1200L)
            }
        )
    }

    private fun executeHudCommand(command: String) {
        if (com.pr4nav.jarvis.voice.WakeWordEngine.isStopCommand(command)) {
            stopAssistantSpeech()
            if (isExpanded) {
                mainHandler.postDelayed({ startOverlayVoiceFlow() }, 400L)
            }
            return
        }

        val voiceSession = com.pr4nav.jarvis.session.JarvisSessionManager.getActiveSession(
            applicationContext,
            com.pr4nav.jarvis.session.SessionType.VOICE_CHAT
        )
        val userMsg = SessionMessage(sender = "user", text = command)
        com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
            applicationContext,
            voiceSession,
            userMsg
        )

        UnifiedAssistantDispatcher.execute(applicationContext, command) { res ->
            val cleanReply = res.jarvisResponse.text
            val agentMsg = SessionMessage(
                id = "agent_${System.currentTimeMillis()}",
                sender = "agent",
                text = cleanReply,
                steps = listOf("Model: ${res.modelName}", "Trace: ${res.thinkingTrace}"),
                isSuccess = res.handled,
                toolCall = if (res.toolResult != null) res.source.label else null
            )
            com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                applicationContext,
                voiceSession,
                agentMsg
            )

            mainHandler.post {
                hudSubtitle?.text = "Jarvis"
                setChatActive(true)
                hudChatAdapter.addMessage(agentMsg)
                hudChatRecycler?.smoothScrollToPosition(hudChatAdapter.itemCount - 1)

                val speech = res.jarvisResponse.speechText
                val display = if (speech.isNotBlank()) speech else res.jarvisResponse.text

                // Speak response with real-time word-by-word highlight!
                val agentKey = agentMsg.id
                voiceEngine?.speak(
                    text = display,
                    interrupt = true,
                    onWordSpoken = { start, end ->
                        hudChatAdapter.setSpeakingHighlight(agentKey, start, end)
                    },
                    onDone = {
                        hudChatAdapter.clearSpeakingHighlight()
                        mainHandler.postDelayed({
                            if (isExpanded) {
                                hudSubtitle?.text = "Listening"
                                hudSubtitle?.setTextColor(Color.parseColor("#99FFFFFF"))
                                startOverlayVoiceFlow() // ALWAYS LISTENING WHILE OVERLAY IS OPEN!
                            }
                        }, 400L)
                    }
                )
            }
        }
    }

    private fun stopAssistantSpeech() {
        voiceEngine?.stopSpeaking()
        hudChatAdapter.clearSpeakingHighlight()
        hudSubtitle?.text = "Stopped"
    }

    private fun hideHudView() {
        stopAssistantSpeech()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_HUD -> {
                expandToHud(preserveSession = false)
            }
            ACTION_HIDE_HUD -> {
                hideHudView()
            }
            ACTION_TOGGLE_BUBBLE -> {
                if (isExpanded) minimizeToBubble() else expandToHud(preserveSession = true)
            }
            ACTION_TRIGGER_VOICE -> {
                onPillClicked()
            }
            ACTION_EXECUTE_COMMAND -> {
                val cmd = intent.getStringExtra("command")
                if (!cmd.isNullOrBlank()) {
                    mainHandler.post {
                        if (!isExpanded) expandToHud(preserveSession = true)
                        hudSubtitle?.text = "Thinking..."
                        setChatActive(true)
                        val userMsg = SessionMessage(sender = "user", text = cmd)
                        hudChatAdapter.addMessage(userMsg)
                        hudChatRecycler?.smoothScrollToPosition(hudChatAdapter.itemCount - 1)
                        executeHudCommand(cmd)
                    }
                }
            }
            else -> {
                expandToHud(preserveSession = false)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        com.pr4nav.jarvis.voice.JarvisVoiceService.unregisterObserver(coreObserver)
        releaseHudVideo()
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
