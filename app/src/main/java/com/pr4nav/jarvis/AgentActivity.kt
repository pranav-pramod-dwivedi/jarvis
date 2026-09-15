package com.pr4nav.jarvis

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pr4nav.jarvis.chat.AgentBubbles
import com.pr4nav.jarvis.chat.AgentStreamEvent
import com.pr4nav.jarvis.chat.AgentTurnRenderer
import com.pr4nav.jarvis.chat.ChatUi
import com.pr4nav.jarvis.chat.Markdown
import com.pr4nav.jarvis.chat.MessageChunker
import com.pr4nav.jarvis.voice.JarvisVoiceEngine
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Modern AI Agent Chat & Execution Stream UI.
 * Replicates the Dribbble AI Agent UI animation for autonomous platforms:
 * - Real-time "Thinking...", "Resolving Tool...", "Executing..." step progression
 * - Dark fintech orange-gradient theme
 * - Interactive prompt cards
 * - Voice STT & TTS integration
 * - Linked navigation to all JARVIS pages (Files, Commander, Terminal, Diagnostics, Services, Playground, Agy)
 */
class AgentActivity : AppCompatActivity() {

    private lateinit var scroller: ScrollView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var input: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var boxThinking: LinearLayout
    private lateinit var thinkingTitle: TextView
    private lateinit var thinkingDetail: TextView
    private lateinit var agentCtx: TextView

    private lateinit var txtSessionTitle: TextView
    private lateinit var btnSessionHistory: Button
    private lateinit var btnNewSession: Button
    private lateinit var pillCurrentModel: TextView
    private lateinit var pillCurrentRoute: TextView
    private lateinit var badgeHighReasoning: TextView
    private lateinit var currentSession: com.pr4nav.jarvis.session.JarvisSession
    private var voiceEngine: JarvisVoiceEngine? = null
    private var lastSubmittedPrompt: String = ""
    @Volatile private var isCurrentTaskCancelled = false
    private var activeExecutionThread: Thread? = null
    private var activeRenderer: AgentTurnRenderer? = null
    private var lastSubmitText: String = ""
    private var lastSubmitAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent)

        scroller = findViewById(R.id.agent_scroller)
        messagesContainer = findViewById(R.id.messages_container)
        input = findViewById(R.id.agent_input)
        btnSend = findViewById(R.id.agent_send)
        btnMic = findViewById(R.id.btn_voice_mic)
        boxThinking = findViewById(R.id.box_thinking)
        thinkingTitle = findViewById(R.id.thinking_title)
        thinkingDetail = findViewById(R.id.thinking_detail)
        agentCtx = findViewById(R.id.agent_ctx)

        txtSessionTitle = findViewById(R.id.txt_current_session_title)
        btnSessionHistory = findViewById(R.id.btn_sessions_history)
        btnNewSession = findViewById(R.id.btn_new_session)
        pillCurrentModel = findViewById(R.id.pill_current_model)
        pillCurrentRoute = findViewById(R.id.pill_current_route)
        badgeHighReasoning = findViewById(R.id.badge_high_reasoning)

        pillCurrentModel.setOnClickListener { showModelPickerDialog() }
        pillCurrentRoute.setOnClickListener { showRoutePickerDialog() }

        findViewById<View>(R.id.btn_cancel_task)?.setOnClickListener {
            cancelCurrentTask()
        }

        voiceEngine = JarvisVoiceEngine.getInstance(this)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        // Setup Session Switcher & History
        setupSessionControls()

        // Setup All Pages dialog popup
        findViewById<View>(R.id.btn_all_pages)?.setOnClickListener {
            showAllPagesDialog()
        }

        // Setup horizontal quick-nav tabs (Files, Services, Playground, Commander, Terminal, Diagnostics, AGY)
        setupQuickNavTabs()

        // Setup fast task selection Prompt Cards
        setupPromptCards()

        // SVG iconography for input bar + tabs + chips (no emojis on this surface)
        styleStaticIcons()

        btnSend.setOnClickListener { submit(input.text.toString().trim()) }
        input.setOnEditorActionListener { _, _, _ ->
            submit(input.text.toString().trim())
            true
        }

        btnMic.setOnClickListener {
            startVoiceListening()
        }

        agentCtx.setOnClickListener {
            showModeSelectorDialog()
        }

        updateCtx()
        handleWakeWordIntent(intent)
    }

    private fun styleStaticIcons() {
        // Input bar SVG icons.
        try {
            findViewById<ImageButton>(R.id.agent_send)?.setImageResource(R.drawable.ic_send)
            findViewById<ImageButton>(R.id.btn_voice_mic)?.setImageResource(R.drawable.ic_mic)
        } catch (_: Exception) { }
        // Prompt chip icons
        setStartIcon(R.id.prompt_card_1, R.drawable.ic_phone)
        setStartIcon(R.id.prompt_card_2, R.drawable.ic_speaker)
        setStartIcon(R.id.prompt_card_3, R.drawable.ic_search)
        setStartIcon(R.id.prompt_card_4, R.drawable.ic_window)
        setStartIcon(R.id.prompt_card_5, R.drawable.ic_folder)
        setStartIcon(R.id.txt_current_session_title, R.drawable.ic_calendar)
    }

    private fun setStartIcon(viewId: Int, iconRes: Int) {
        try {
            val tv = findViewById<TextView>(viewId) ?: return
            val d = ContextCompat.getDrawable(this, iconRes)?.mutate()
            d?.setBounds(0, 0, ChatUi.dp(this, 14), ChatUi.dp(this, 14))
            d?.let {
                val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(it)
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, Color.parseColor("#FF9E44"))
                tv.setCompoundDrawables(wrapped, null, null, null)
                tv.compoundDrawablePadding = ChatUi.dp(this, 6)
            }
        } catch (_: Exception) { }
    }

    private fun setupSessionControls() {        // Load active session or create initial session
        val session = com.pr4nav.jarvis.session.JarvisSessionManager.getActiveSession(
            this,
            com.pr4nav.jarvis.session.SessionType.AGENT_CHAT
        )
        loadSession(session)

        btnSessionHistory.setOnClickListener {
            com.pr4nav.jarvis.session.SessionHistoryDialog(
                context = this,
                filterType = com.pr4nav.jarvis.session.SessionType.AGENT_CHAT,
                currentSessionId = currentSession.id,
                onSessionSelected = { selected ->
                    loadSession(selected)
                },
                onNewSessionRequested = {
                    createNewSession()
                }
            ).show()
        }

        btnNewSession.setOnClickListener {
            createNewSession()
        }
    }

    private fun createNewSession() {
        val newSession = com.pr4nav.jarvis.session.JarvisSessionManager.createSession(
            this,
            com.pr4nav.jarvis.session.SessionType.AGENT_CHAT,
            workingDir = SessionState.dir
        )
        loadSession(newSession)
        Toast.makeText(this, "Started new session: ${newSession.title}", Toast.LENGTH_SHORT).show()
    }

    private fun loadSession(session: com.pr4nav.jarvis.session.JarvisSession) {
        currentSession = session
        txtSessionTitle.text = session.title
        SessionState.dir = session.workingDir

        // Render previous messages from session history with the new components.
        messagesContainer.removeAllViews()

        if (session.messages.isEmpty()) {
            messagesContainer.addView(
                AgentBubbles.agentCard(
                    this,
                    "Neural agent ready. Deterministic tool routing and autonomous execution are active.\n\nWorking directory: `${session.workingDir}`",
                    caption = session.title,
                    actions = null
                )
            )
        } else {
            for (m in session.messages) {
                if (m.sender == "user") {
                    messagesContainer.addView(AgentBubbles.userBubble(this, m.text))
                } else {
                    renderHistoryAgent(m)
                }
            }
        }
        // Restore conversation history into the in-memory context so the model
        // has continuity after a restart or session switch
        com.pr4nav.jarvis.context.ConversationalContext.clear()
        val pairs = session.messages.zipWithNext()
        for ((a, b) in pairs) {
            if (a.sender == "user" && b.sender == "agent" && a.text.isNotBlank() && b.text.isNotBlank()) {
                com.pr4nav.jarvis.context.ConversationalContext.recordTurn(a.text, b.text)
            }
        }
        scrollToBottom()
        updateCtx()
    }

    private fun renderHistoryAgent(m: com.pr4nav.jarvis.session.SessionMessage) {
        var thinking = m.thinking
        val toolRows = mutableListOf<String>()
        for (s in m.steps) {
            val t = s.trim()
            when {
                t.startsWith("Reasoning:") -> {
                    if (thinking.isBlank()) thinking = t.removePrefix("Reasoning:").trim()
                }
                t.startsWith("Model:") || t.startsWith("• Model:") ||
                    t.startsWith("Latency:") || t.startsWith("• Latency:") ||
                    t.contains("Full Power Engine") || t.contains("Needle 2 Reflex ·") ||
                    t.contains("Kira Full Power") -> { /* caption-level meta, skip */ }
                t.isNotBlank() -> toolRows.add(Markdown.stripToPlain(t).take(220))
            }
        }
        // Legacy sessions stored reasoning inside steps with an emoji prefix.
        if (thinking.isBlank()) {
            val legacy = m.steps.firstOrNull { it.contains("Reasoning:") }
            if (legacy != null) thinking = legacy.substringAfter("Reasoning:").trim()
        }
        if (thinking.isNotBlank()) {
            messagesContainer.addView(AgentBubbles.staticThinking(this, thinking))
        }
        toolRows.forEach { messagesContainer.addView(AgentBubbles.toolHistoryRow(this, it)) }
        val chunks = MessageChunker.chunk(m.text.ifBlank { "Done." })
        val acts = historyActions(m.text)
        chunks.forEach { messagesContainer.addView(AgentBubbles.agentCard(this, it, null, acts)) }
    }

    private fun historyActions(fullText: String): AgentBubbles.Actions {
        return AgentBubbles.Actions(
            fullText = fullText,
            prompt = lastSubmittedPrompt,
            onListen = { t -> voiceEngine?.speak(t, interrupt = true) },
            onRegenerate = { p -> showRegenerateDialog(p) },
            onStopSpeak = { voiceEngine?.stopSpeaking() }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeWordIntent(intent)
    }

    private fun handleWakeWordIntent(intent: Intent?) {
        val promptExtra = intent?.getStringExtra("prompt") ?: intent?.getStringExtra("auto_submit")
        if (!promptExtra.isNullOrBlank()) {
            submit(promptExtra.trim())
            return
        }

        if (intent?.getBooleanExtra("from_wake_word", false) == true) {
            val wakeWord = intent.getStringExtra("wake_word") ?: "Jarvis"
            showThinking("$wakeWord is listening…", "Hands-free session active. Speak your command.")
        }
    }

    private val voiceCoreObserver = object : com.pr4nav.jarvis.voice.JarvisVoiceService.CoreObserver {
        override fun onStateChanged(state: com.pr4nav.jarvis.voice.JarvisVoiceService.VoiceState, detail: String) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    updateCtx()
                }
            }
        }

        override fun onSpeechRecognized(text: String) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && text.isNotBlank()) {
                    renderUserBubble(text)
                }
            }
        }

        override fun onResponseSynthesized(speechText: String, fullSummary: String) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && fullSummary.isNotBlank()) {
                    messagesContainer.addView(
                        AgentBubbles.agentCard(this@AgentActivity, fullSummary, null, null)
                    )
                    scrollToBottom()
                }
            }
        }

        override fun onThinkingTrace(trace: String) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && trace.isNotBlank()) {
                    thinkingDetail.text = trace
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.pr4nav.jarvis.voice.JarvisVoiceService.registerObserver(voiceCoreObserver)
    }

    override fun onResume() {
        super.onResume()
        updateCtx()
    }

    override fun onStop() {
        super.onStop()
        com.pr4nav.jarvis.voice.JarvisVoiceService.unregisterObserver(voiceCoreObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        com.pr4nav.jarvis.voice.JarvisVoiceService.unregisterObserver(voiceCoreObserver)
        voiceEngine?.destroy()
    }

    private fun updateCtx() {
        val mode = com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.getAgentMode(this)
        val termuxState = if (Shell.termuxReachable()) "UP" else "DOWN"
        val coreState = com.pr4nav.jarvis.voice.JarvisVoiceService.currentState.name
        agentCtx.text = "Mode: ${mode.displayName} · Core: $coreState · termux: $termuxState"
        updateModelPill()
    }

    private fun updateModelPill() {
        val raw = com.pr4nav.jarvis.llm.KiraClient.getModel(this)
        val isAuto = raw == com.pr4nav.jarvis.llm.KiraClient.MODEL_AUTO
        val effective = if (isAuto) com.pr4nav.jarvis.llm.KiraClient.lastAutoModel() else raw
        val label = com.pr4nav.jarvis.llm.KiraClient.modelLabel(effective)
        val solo = com.pr4nav.jarvis.llm.KiraClient.isSoloModel(this)
        pillCurrentModel.text = if (isAuto) {
            "AUTO · $label ▾"
        } else {
            "MODEL · $label${if (solo) " · SOLO" else ""} ▾"
        }
        badgeHighReasoning.visibility =
            if (com.pr4nav.jarvis.llm.KiraClient.isHighReasoning(effective)) View.VISIBLE else View.GONE
        val route = com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.getRoute(this)
        pillCurrentRoute.text =
            "ROUTE · ${com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.routeShort(route)} ▾"
    }

    /** Route pill opens the full routing harness screen. */
    fun showRoutePickerDialog() {
        startActivity(Intent(this, RouteHarnessActivity::class.java))
    }

    /** Model switcher on the chat page (delegates to the shared picker). */
    fun showModelPickerDialog() {
        com.pr4nav.jarvis.llm.KiraModelPicker.show(this) { updateModelPill() }
    }

    private fun showModeSelectorDialog() {
        val providers = com.pr4nav.jarvis.llm.AIProvider.values().toList()
        val items = (providers.map { p ->
            "${p.title}\n${p.hint} · current: ${p.currentModel(this)}"
        } + "Needle Only (deterministic, offline)\nFast on-device actions, no models").toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select provider")
            .setItems(items) { _, which ->
                if (which < providers.size) {
                    val p = providers[which]
                    com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.setRoute(this, listOf(p.engine))
                    updateModelPill()
                    updateCtx()
                    Toast.makeText(this, "${p.title} active (solo engine)", Toast.LENGTH_SHORT).show()
                } else {
                    com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.setAgentMode(
                        this, com.pr4nav.jarvis.router.AgentExecutionMode.NEEDLE_ONLY
                    )
                    updateCtx()
                    Toast.makeText(this, "Needle Only (offline deterministic)", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("Kira AI") { _, _ ->
                showConfigureKiraDialog()
            }
            .setNeutralButton("Provider keys") { _, _ ->
                startActivity(Intent(this, ProviderKeysActivity::class.java))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showConfigureKiraDialog() {
        val currentKey = com.pr4nav.jarvis.llm.KiraClient.getApiKey(this)
        val currentModel = com.pr4nav.jarvis.llm.KiraClient.getModel(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val info = android.widget.TextView(this).apply {
            text = "Kira AI Platform (Primary Chat & Code)\nCascade: ${com.pr4nav.jarvis.llm.KiraClient.FREE_MODEL_CASCADE.joinToString(" > ")}\nModel: $currentModel"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
        layout.addView(info)

        val edit = EditText(this).apply {
            setText(currentKey)
            hint = "Kira API Key (Bearer ...)"
            setPadding(30, 25, 30, 25)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(edit)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Configure Kira AI Key & Models")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newKey = edit.text.toString().trim()
                com.pr4nav.jarvis.llm.KiraClient.setApiKey(this, newKey)
                Toast.makeText(this, if (newKey.isNotEmpty()) "Kira API Key Saved!" else "Kira Key Cleared (Using Free Cascade)", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Model") { _, _ ->
                showModelPickerDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setupQuickNavTabs() {
        // Dev tabs removed from main chat UI — accessible via the pages hub button instead
    }

    private fun setupPromptCards() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val suggestions = when {
            hour in 11..15 -> listOf("What's the weather?", "Set a timer", "Play music", "Take a screenshot", "Battery level")
            hour in 16..21 -> listOf("Play chill music", "Take me home", "Set a reminder", "Recent downloads", "Take a screenshot")
            else           -> listOf("Good night note", "Set alarm", "Play music", "Battery level", "Take a screenshot")
        }
        val ids = listOf(R.id.prompt_card_1, R.id.prompt_card_2, R.id.prompt_card_3, R.id.prompt_card_4, R.id.prompt_card_5)
        ids.forEachIndexed { i, id ->
            val tv = findViewById<android.widget.TextView>(id)
            tv?.text = suggestions.getOrElse(i) { "" }
            tv?.setOnClickListener { submit(suggestions.getOrElse(i) { "" }) }
        }
    }

    private fun showAllPagesDialog() {
        val pages = arrayOf(
            "AI Model Hub & Weights",
            "Routing Harness (engines · models · latency)",
            "Gemini Live Playground",
            "Floating Companion HUD Overlay",
            "Voice & Assistant Settings",
            "Artifacts & Mini-Apps",
            "File Manager & Storage Browser",
            "Connected Services & Local AI",
            "Canonical Tool Playground",
            "Commander / Quick Action Console",
            "Termux Linux Terminal",
            "System Diagnostics & Benchmarks",
            "Gaming Mode / Force Stop",
            "App Permissions Manager"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("JARVIS Pages Hub")
            .setItems(pages) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, com.pr4nav.jarvis.voice.ModelHubActivity::class.java))
                    1 -> startActivity(Intent(this, RouteHarnessActivity::class.java))
                    2 -> startActivity(Intent(this, LivePlaygroundActivity::class.java))
                    3 -> com.pr4nav.jarvis.companion.JarvisOverlayService.showHud(this)
                    4 -> startActivity(Intent(this, com.pr4nav.jarvis.voice.VoiceSettingsActivity::class.java))
                    5 -> startActivity(Intent(this, ArtifactsActivity::class.java))
                    6 -> startActivity(Intent(this, BrowserActivity::class.java))
                    7 -> startActivity(Intent(this, ConnectedServicesActivity::class.java))
                    8 -> startActivity(Intent(this, ToolPlaygroundActivity::class.java))
                    9 -> startActivity(Intent(this, CommanderActivity::class.java))
                    10 -> startActivity(Intent(this, TerminalActivity::class.java))
                    11 -> startActivity(Intent(this, DiagnosticsActivity::class.java))
                    12 -> com.pr4nav.jarvis.system.GamingModeManager.forceStopAll(this)
                    13 -> startActivity(Intent(this, PermissionsActivity::class.java))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startVoiceListening() {
        btnMic.alpha = 0.5f
        showThinking("Listening for voice command…", "Speak clearly into microphone")
        voiceEngine?.startListening(
            activity = this,
            onPartial = { partial ->
                runOnUiThread {
                    showThinking("Listening: $partial", "Transcribing…")
                }
            },
            onResult = { result ->
                runOnUiThread {
                    btnMic.alpha = 1.0f
                    hideThinking()
                    input.setText(result)
                    submit(result)
                }
            },
            onError = { err ->
                runOnUiThread {
                    btnMic.alpha = 1.0f
                    hideThinking()
                    Toast.makeText(this, "Voice error: $err", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showThinking(title: String, detail: String) {
        boxThinking.visibility = View.VISIBLE
        thinkingTitle.text = title
        thinkingDetail.text = detail
        scrollToBottom()
    }

    private fun hideThinking() {
        boxThinking.visibility = View.GONE
    }

    private fun scrollToBottom() {
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderUserBubble(text: String) {
        messagesContainer.addView(AgentBubbles.userBubble(this, text))
        scrollToBottom()
    }

    private fun showRegenerateDialog(prompt: String) {
        com.pr4nav.jarvis.llm.KiraModelPicker.show(this) {
            updateModelPill()
            updateCtx()
            submit(prompt)
        }
    }

    private fun addUserMessage(text: String) {
        renderUserBubble(text)
        scrollToBottom()

        // Persist to active session
        if (::currentSession.isInitialized) {
            com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                this,
                currentSession,
                com.pr4nav.jarvis.session.SessionMessage(
                    sender = "user",
                    text = text
                )
            )
            // Auto-title: use first user message as the session title (like Claude/ChatGPT)
            if (currentSession.messages.size <= 2 && currentSession.title.matches(Regex("\\d{2} .+ \\d{4}, .+"))) {
                val autoTitle = text.trim().take(48).let { if (it.length == 48) "$it…" else it }
                currentSession.title = autoTitle
                com.pr4nav.jarvis.session.JarvisSessionManager.saveSession(this, currentSession)
                runOnUiThread { txtSessionTitle.text = autoTitle }
            }
        }
    }

    /**
     * System/info card (built-in commands, cancellations, errors): markdown body
     * with icon actions, persisted to the session. No emojis.
     */
    private fun addExecutionStepCard(
        title: String,
        steps: List<String>,
        isSuccess: Boolean,
        finalSummary: String,
        saveToHistory: Boolean = true
    ) {
        val md = buildString {
            append("**").append(title).append("**")
            val cleanSteps = steps.map { it.trim() }.filter { it.isNotBlank() }
            if (cleanSteps.isNotEmpty()) {
                append("\n\n")
                cleanSteps.forEach { append("- ").append(it).append("\n") }
            }
            if (finalSummary.isNotBlank()) {
                append("\n").append(finalSummary.trim())
            }
        }.trim()
        val acts = AgentBubbles.Actions(
            fullText = finalSummary.ifBlank { md },
            prompt = lastSubmittedPrompt,
            onListen = { t -> voiceEngine?.speak(t, interrupt = true) },
            onRegenerate = { pr -> showRegenerateDialog(pr) },
            onStopSpeak = { voiceEngine?.stopSpeaking() }
        )
        messagesContainer.addView(AgentBubbles.agentCard(this, md, null, acts))
        scrollToBottom()

        // Persist to active session
        if (saveToHistory && ::currentSession.isInitialized) {
            com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                this,
                currentSession,
                com.pr4nav.jarvis.session.SessionMessage(
                    sender = "agent",
                    text = md,
                    steps = steps,
                    isSuccess = isSuccess
                )
            )
        }

        // Speak outcome via TTS
        if (finalSummary.isNotBlank()) {
            voiceEngine?.speak(
                com.pr4nav.jarvis.response.UserResponseSanitizer.sanitizeForSpeech(finalSummary),
                interrupt = false
            )
        }
    }

    @Volatile private var activeTaskId: String? = null

    private fun cancelCurrentTask() {
        isCurrentTaskCancelled = true
        activeTaskId = null
        activeRenderer?.cancel()
        activeRenderer = null
        activeExecutionThread?.interrupt()
        activeExecutionThread = null
        voiceEngine?.stopSpeaking()
        runOnUiThread {
            hideThinking()
            addExecutionStepCard(
                title = "Task Cancelled",
                steps = listOf("Operation stopped by user request"),
                isSuccess = false,
                finalSummary = "Task execution was cancelled."
            )
        }
    }

    /** Opens a saved artifact: HTML imports into a fresh browser tab, else the file manager. */
    private fun openArtifactFile(filePath: String, title: String) {
        try {
            val f = java.io.File(filePath)
            if (f.exists() && f.extension.lowercase() == "html") {
                val app = com.pr4nav.jarvis.browser.JarvisBrowserAppManager.createApp(
                    ctx = this,
                    appId = "artifact-open-${System.currentTimeMillis()}",
                    title = title,
                    description = "Opened from Artifacts",
                    html = f.readText(),
                    isTemporary = true
                )
                com.pr4nav.jarvis.browser.JarvisBrowserActivity.launch(this, app.id)
            } else {
                startActivity(Intent(this, BrowserActivity::class.java))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Open failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun submit(q: String) {
        if (q.isEmpty()) return
        // Drop accidental double-submits.
        val nowMs = System.currentTimeMillis()
        if (q == lastSubmitText && nowMs - lastSubmitAt < 1200) return
        lastSubmitText = q
        lastSubmitAt = nowMs
        // Interrupt: a new message silently kills the running turn (stale
        // callbacks already check activeTaskId and drop themselves).
        try {
            activeRenderer?.cancel()
        } catch (_: Exception) { }
        activeRenderer = null
        try {
            activeExecutionThread?.interrupt()
        } catch (_: Exception) { }
        activeExecutionThread = null
        voiceEngine?.stopSpeaking()
        hideThinking()
        lastSubmittedPrompt = q
        isCurrentTaskCancelled = false
        val taskId = java.util.UUID.randomUUID().toString()
        activeTaskId = taskId
        input.setText("")
        addUserMessage(q)

        val t = thread {
            try {
                handle(q, taskId)
            } catch (e: Exception) {
                if (!isCurrentTaskCancelled && activeTaskId == taskId) {
                    runOnUiThread {
                        hideThinking()
                        addExecutionStepCard(
                            title = "Execution Failed",
                            steps = listOf("Error during command processing", e.message ?: "Unknown error"),
                            isSuccess = false,
                            finalSummary = "Could not execute: ${e.message}"
                        )
                    }
                }
            }
        }
        activeExecutionThread = t
    }

    private fun handle(q: String, taskId: String? = null) {
        val lower = q.trim().lowercase()
        val arg = q.trim().split(" ", limit = 2).getOrNull(1)?.trim() ?: ""

        // Built-in Developer Utilities
        when {
            lower == "help" -> runOnUiThread {
                if (taskId != null && activeTaskId != taskId) return@runOnUiThread
                addExecutionStepCard(
                    title = "JARVIS Developer & Command Reference",
                    steps = listOf(
                        "Natural Voice & Chat: \"hi\", \"what is quantum computing\", \"take me home\"",
                        "Device Control: \"turn on flashlight\", \"set volume 80%\", \"take screenshot\"",
                        "Linux System: pwd, ls [path], run <cmd> (Termux shell)",
                        "Tools: tools (lists all 23+ canonical tools)"
                    ),
                    isSuccess = true,
                    finalSummary = "Reference loaded."
                )
            }

            lower == "pwd" -> runOnUiThread {
                if (taskId != null && activeTaskId != taskId) return@runOnUiThread
                addExecutionStepCard(
                    title = "Current Working Directory",
                    steps = listOf("Resolved from SessionState"),
                    isSuccess = true,
                    finalSummary = "cwd: ${SessionState.dir}"
                )
            }

            lower.startsWith("ls") -> {
                val p = Fs.resolve(arg.ifBlank { "." })
                val list = Fs.list(p)
                val items = list.take(10).map { (if (it.isDir) "[dir] " else "") + it.name }
                runOnUiThread {
                    if (taskId != null && activeTaskId != taskId) return@runOnUiThread
                    addExecutionStepCard(
                        title = "Directory Listing: $p",
                        steps = items,
                        isSuccess = true,
                        finalSummary = "Found ${list.size} items in $p"
                    )
                }
            }

            lower.startsWith("run ") -> {
                var shellCard: com.pr4nav.jarvis.chat.ToolCallCard? = null
                runOnUiThread {
                    if (taskId == null || activeTaskId == taskId) {
                        shellCard = com.pr4nav.jarvis.chat.ToolCallCard(this, "execute_shell_command", arg)
                        messagesContainer.addView(shellCard)
                        scrollToBottom()
                    }
                }
                val guardErr = CmdGuard.check(arg)
                if (guardErr != null) {
                    runOnUiThread {
                        if (taskId == null || activeTaskId == taskId) {
                            hideThinking()
                            addExecutionStepCard("Command Blocked", listOf(guardErr), false, "Security guard prevented execution.")
                        }
                    }
                    return
                }
                val shellT0 = System.currentTimeMillis()
                val r = Shell.termux(arg, 60_000)
                runOnUiThread {
                    if (taskId != null && activeTaskId != taskId) return@runOnUiThread
                    hideThinking()
                    val out = if (r.out.isNotBlank()) r.out else r.err
                    val rc = r.rc ?: -1
                    shellCard?.finish(out, rc, System.currentTimeMillis() - shellT0)
                    if (shellCard == null) {
                        addExecutionStepCard(
                            title = "Termux Shell",
                            steps = listOf("Exit code: ${r.rc}", "Execution time: ${r.ms}ms", "Via: ${r.via}"),
                            isSuccess = r.rc == 0,
                            finalSummary = if (out.isNotBlank()) out.take(1000) else "(no output)"
                        )
                    } else if (::currentSession.isInitialized) {
                        com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                            this,
                            currentSession,
                            com.pr4nav.jarvis.session.SessionMessage(
                                sender = "agent",
                                text = "Ran `$arg` — exit ${r.rc} in ${r.ms}ms.",
                                steps = listOf("Run · $arg · exit ${r.rc} · ${r.ms}ms"),
                                isSuccess = r.rc == 0,
                                toolCall = "execute_shell_command"
                            )
                        )
                    }
                    scrollToBottom()
                }
            }

            lower == "tools" -> {
                com.pr4nav.jarvis.tools.JarvisToolRegistry.registerAll(this)
                val cat = com.pr4nav.jarvis.tools.JarvisToolRegistry.catalog()
                runOnUiThread {
                    if (taskId != null && activeTaskId != taskId) return@runOnUiThread
                    addExecutionStepCard(
                        title = "Registered Canonical Tools",
                        steps = cat.lines().take(12),
                        isSuccess = true,
                        finalSummary = "All canonical tools ready for autonomous execution."
                    )
                }
            }

            else -> {
                // Event-driven turn: thinking block, tool cards, chunked message bubbles.
                val rendererRef = arrayOfNulls<AgentTurnRenderer>(1)
                val pendingEvents = mutableListOf<AgentStreamEvent>()
                val deliverEvent: (AgentStreamEvent) -> Unit = { ev ->
                    val r = synchronized(pendingEvents) {
                        val rr = rendererRef[0]
                        if (rr == null) {
                            pendingEvents.add(ev)
                        }
                        rr
                    }
                    r?.onEvent(ev)
                }
                val turnListener = object : AgentTurnRenderer.Listener {
                    override fun onTurnFinished(
                        finalText: String,
                        thinking: String,
                        toolSummaries: List<String>,
                        toolNames: String,
                        success: Boolean,
                        model: String,
                        latencyMs: Long
                    ) {
                        if (::currentSession.isInitialized) {
                            try {
                                com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                                    this@AgentActivity,
                                    currentSession,
                                    com.pr4nav.jarvis.session.SessionMessage(
                                        sender = "agent",
                                        text = finalText,
                                        steps = toolSummaries,
                                        isSuccess = success,
                                        toolCall = toolNames.ifBlank { null },
                                        thinking = thinking
                                    )
                                )
                                if (model.isNotBlank()) currentSession.modelUsed = model
                            } catch (_: Exception) { }
                        }
                    }

                    override fun onOpenArtifactFile(filePath: String, title: String, type: String) {
                        openArtifactFile(filePath, title)
                    }

                    override fun onOpenBrowserApp(appId: String) {
                        try {
                            com.pr4nav.jarvis.browser.JarvisBrowserActivity.launch(this@AgentActivity, appId)
                        } catch (e: Exception) {
                            Toast.makeText(this@AgentActivity, "Open failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun bubbleActions(fullText: String): AgentBubbles.Actions {
                        return AgentBubbles.Actions(
                            fullText = fullText,
                            prompt = lastSubmittedPrompt,
                            onListen = { t -> voiceEngine?.speak(t, interrupt = true) },
                            onRegenerate = { pr -> showRegenerateDialog(pr) },
                            onStopSpeak = { voiceEngine?.stopSpeaking() }
                        )
                    }
                }
                runOnUiThread {
                    if (taskId == null || activeTaskId == taskId) {
                        val r = AgentTurnRenderer(this, messagesContainer, scroller, q, turnListener)
                        synchronized(pendingEvents) {
                            rendererRef[0] = r
                            activeRenderer = r
                            val backlog = pendingEvents.toList()
                            pendingEvents.clear()
                            backlog.forEach { r.onEvent(it) }
                        }
                    }
                }

                com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.execute(
                    context = this,
                    rawQuery = q,
                    onStatus = { status ->
                        if (taskId == null || (activeTaskId == taskId && !isCurrentTaskCancelled)) {
                            if (status.isNotBlank() && !status.contains("null", ignoreCase = true)) {
                                deliverEvent(AgentStreamEvent.Status(status))
                            }
                        }
                    },
                    onChunk = null,
                    onResult = { res ->
                        runOnUiThread {
                            if (taskId != null && (activeTaskId != taskId || isCurrentTaskCancelled)) {
                                return@runOnUiThread
                            }
                            hideThinking()
                            val renderer = rendererRef[0]
                            if (renderer != null && !renderer.isFinished()) {
                                // Safety net: a path that emitted no Final event.
                                val rawReply = if (res.jarvisResponse.text.isNotBlank() && !res.jarvisResponse.text.equals("null", ignoreCase = true)) {
                                    res.jarvisResponse.text
                                } else if (res.speechResponse.isNotBlank() && !res.speechResponse.equals("null", ignoreCase = true)) {
                                    res.speechResponse
                                } else {
                                    "Action completed successfully."
                                }
                                val (_, cleanReply) = com.pr4nav.jarvis.response.UserResponseSanitizer.stripThinking(rawReply)
                                val finalReply = if (cleanReply.isNotBlank() && !cleanReply.equals("null", ignoreCase = true)) cleanReply else rawReply
                                renderer.onEvent(
                                    AgentStreamEvent.Final(
                                        text = finalReply,
                                        model = res.modelName,
                                        latencyMs = res.latencyMs,
                                        handled = res.handled,
                                        thinkingTrace = res.thinkingTrace
                                    )
                                )
                            }

                            // Speak response via TTS only if not cancelled (empty = voice already played it)
                            if (taskId == null || (activeTaskId == taskId && !isCurrentTaskCancelled)) {
                                val say = res.jarvisResponse.speechText
                                if (say.isNotBlank() && !say.equals("null", ignoreCase = true)) {
                                    voiceEngine?.speak(say, interrupt = false)
                                }
                            }
                            updateCtx()
                        }
                    },
                    onEvent = { ev ->
                        if (taskId == null || (activeTaskId == taskId && !isCurrentTaskCancelled)) {
                            deliverEvent(ev)
                        }
                    }
                )
            }
        }
    }
}
