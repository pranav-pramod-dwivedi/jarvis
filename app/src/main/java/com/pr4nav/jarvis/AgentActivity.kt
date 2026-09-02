package com.pr4nav.jarvis

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var currentSession: com.pr4nav.jarvis.session.JarvisSession
    private var voiceEngine: JarvisVoiceEngine? = null
    private var lastSubmittedPrompt: String = ""
    @Volatile private var isCurrentTaskCancelled = false
    private var activeExecutionThread: Thread? = null

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

        findViewById<View>(R.id.btn_cancel_task)?.setOnClickListener {
            cancelCurrentTask()
        }

        findViewById<View>(R.id.btn_race_agy)?.setOnClickListener {
            val promptToRace = lastSubmittedPrompt
            if (promptToRace.isNotBlank()) {
                showThinking("⚡ Racing with AGY Agent…", "Executing prompt in parallel via AGY inside PRoot Linux")
                Thread {
                    try {
                        val agyRes = Shell.agy(promptToRace, timeoutMs = 60_000)
                        runOnUiThread {
                            hideThinking()
                            val cleanOut = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitize(agyRes.out, promptToRace)
                            val steps = listOf(
                                "Parallel Engine: AGY Autonomous Agent (PRoot Linux)",
                                "Execution time: ${agyRes.ms}ms",
                                "Exit code: ${agyRes.rc}"
                            )
                            addExecutionStepCard(
                                title = "🤖 [AGY Parallel Result · ${agyRes.ms}ms]",
                                steps = steps,
                                isSuccess = agyRes.rc == 0,
                                finalSummary = if (cleanOut.isNotBlank()) cleanOut else if (agyRes.err.isNotBlank()) agyRes.err else "(no output)"
                            )
                            if (cleanOut.isNotBlank()) {
                                voiceEngine?.speak(cleanOut, interrupt = false)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            hideThinking()
                            Toast.makeText(this@AgentActivity, "AGY race error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                Toast.makeText(this, "No active prompt to race", Toast.LENGTH_SHORT).show()
            }
        }

        voiceEngine = JarvisVoiceEngine(this)

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

    private fun setupSessionControls() {
        // Load active session or create initial session
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
        txtSessionTitle.text = "📅 ${session.title}"
        SessionState.dir = session.workingDir

        // Render previous messages from session history
        messagesContainer.removeAllViews()

        if (session.messages.isEmpty()) {
            addExecutionStepCard(
                title = "JARVIS Neural Agent Ready",
                steps = listOf(
                    "Session initialized: ${session.title}",
                    "Deterministic tool routing: Active",
                    "Autonomous coding & execution: Active",
                    "Working directory: ${session.workingDir}"
                ),
                isSuccess = true,
                finalSummary = "Ready for complex autonomous tasks and natural conversation.",
                saveToHistory = false
            )
        } else {
            for (m in session.messages) {
                if (m.sender == "user") {
                    renderUserBubble(m.text)
                } else {
                    renderStepCard(m.steps, m.isSuccess, m.text)
                }
            }
        }
        scrollToBottom()
        updateCtx()
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
            showThinking("🎙 $wakeWord is listening…", "Hands-free session active. Speak your command.")
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
                    renderStepCard(emptyList(), true, fullSummary)
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
    }

    private fun showModeSelectorDialog() {
        val modes = com.pr4nav.jarvis.router.AgentExecutionMode.values()
        val items = modes.map { "${it.displayName}\n${it.description}" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Intelligence & Router Mode")
            .setItems(items) { _, which ->
                val chosen = modes[which]
                com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.setAgentMode(this, chosen)
                updateCtx()
                Toast.makeText(this, "Switched to ${chosen.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Groq Key") { _, _ ->
                showConfigureGroqDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showConfigureGroqDialog() {
        val currentKey = com.pr4nav.jarvis.llm.GroqClient.getApiKey(this)
        val currentModel = com.pr4nav.jarvis.llm.GroqClient.getModel(this)
        val metrics = com.pr4nav.jarvis.llm.GroqClient.getUsageMetrics(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val info = android.widget.TextView(this).apply {
            text = "⚡ Groq LPU (Max 8,192 tokens/msg)\nQuotas: ${metrics.rpdUsed}/245 RPD · ${metrics.currentTpm}/65k TPM\nModel: $currentModel"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
        layout.addView(info)

        val edit = EditText(this).apply {
            setText(currentKey)
            hint = "Groq API Key (gsk_...)"
            setPadding(30, 25, 30, 25)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(edit)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚡ Configure Groq API Key & Quotas")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newKey = edit.text.toString().trim()
                com.pr4nav.jarvis.llm.GroqClient.setApiKey(this, newKey)
                Toast.makeText(this, if (newKey.isNotEmpty()) "Groq API Key Saved!" else "Groq API Key Cleared", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Model") { _, _ ->
                val models = arrayOf(
                    "⚡ groq/compound-mini (Default · Ultra-Fast Compound Agent)",
                    "🧠 groq/compound (Complex Multi-Tool Compound Agent)",
                    "llama-3.3-70b-versatile (Flagship 70B)",
                    "llama-3.1-8b-instant (Fast 8B)",
                    "mixtral-8x7b-32768 (32k Context)",
                    "📥 Fetch Available Models from Groq API..."
                )
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Select Groq Model")
                    .setItems(models) { _, which ->
                        when (which) {
                            0 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "groq/compound-mini")
                                Toast.makeText(this, "Model set to groq/compound-mini (Default)", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "groq/compound")
                                Toast.makeText(this, "Model set to groq/compound", Toast.LENGTH_SHORT).show()
                            }
                            2 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "llama-3.3-70b-versatile")
                                Toast.makeText(this, "Model set to llama-3.3-70b-versatile", Toast.LENGTH_SHORT).show()
                            }
                            3 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "llama-3.1-8b-instant")
                                Toast.makeText(this, "Model set to llama-3.1-8b-instant", Toast.LENGTH_SHORT).show()
                            }
                            4 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "mixtral-8x7b-32768")
                                Toast.makeText(this, "Model set to mixtral-8x7b-32768", Toast.LENGTH_SHORT).show()
                            }
                            5 -> {
                                Toast.makeText(this, "Fetching models from Groq...", Toast.LENGTH_SHORT).show()
                                com.pr4nav.jarvis.llm.GroqClient.fetchAvailableModels(
                                    context = this,
                                    onSuccess = { fetched ->
                                        runOnUiThread {
                                            if (fetched.isEmpty()) {
                                                Toast.makeText(this, "No models returned by Groq", Toast.LENGTH_SHORT).show()
                                                return@runOnUiThread
                                            }
                                            androidx.appcompat.app.AlertDialog.Builder(this)
                                                .setTitle("Available Groq Models (${fetched.size})")
                                                .setItems(fetched.toTypedArray()) { _, fWhich ->
                                                    val chosen = fetched[fWhich]
                                                    com.pr4nav.jarvis.llm.GroqClient.setModel(this, chosen)
                                                    Toast.makeText(this, "Model set to $chosen", Toast.LENGTH_SHORT).show()
                                                }
                                                .setNegativeButton("Cancel", null)
                                                .show()
                                        }
                                    },
                                    onError = { err ->
                                        runOnUiThread {
                                            Toast.makeText(this, "Fetch error: $err", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupQuickNavTabs() {
        findViewById<View>(R.id.tab_files)?.setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<View>(R.id.tab_services)?.setOnClickListener {
            startActivity(Intent(this, ConnectedServicesActivity::class.java))
        }
        findViewById<View>(R.id.tab_playground)?.setOnClickListener {
            startActivity(Intent(this, ToolPlaygroundActivity::class.java))
        }
        findViewById<View>(R.id.tab_commander)?.setOnClickListener {
            startActivity(Intent(this, CommanderActivity::class.java))
        }
        findViewById<View>(R.id.tab_terminal)?.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        findViewById<View>(R.id.tab_diagnostics)?.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<View>(R.id.tab_agy)?.setOnClickListener {
            startActivity(Intent(this, AgyActivity::class.java))
        }
    }

    private fun setupPromptCards() {
        findViewById<TextView>(R.id.prompt_card_1)?.setOnClickListener { submit("Call Akhil") }
        findViewById<TextView>(R.id.prompt_card_2)?.setOnClickListener { submit("Play chill music") }
        findViewById<TextView>(R.id.prompt_card_3)?.setOnClickListener { submit("Take me home") }
        findViewById<TextView>(R.id.prompt_card_4)?.setOnClickListener { submit("Take a screenshot") }
        findViewById<TextView>(R.id.prompt_card_5)?.setOnClickListener { submit("Find my recent downloads") }
    }

    private fun showAllPagesDialog() {
        val pages = arrayOf(
            "🧠 AI Model Hub & Weights",
            "✨ Floating Companion HUD Overlay",
            "🎙️ Voice & Assistant Settings",
            "📁 File Manager & Storage Browser",
            "⚡ Connected Services & Local AI",
            "🛠️ Canonical Tool Playground",
            "🎯 Commander / Quick Action Console",
            "💻 Termux Linux Terminal",
            "📊 System Diagnostics & Benchmarks",
            "🚀 Antigravity (AGY) Console",
            "🔒 App Permissions Manager"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("JARVIS Pages Hub")
            .setItems(pages) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, com.pr4nav.jarvis.voice.ModelHubActivity::class.java))
                    1 -> com.pr4nav.jarvis.companion.JarvisOverlayService.showHud(this)
                    2 -> startActivity(Intent(this, com.pr4nav.jarvis.voice.VoiceSettingsActivity::class.java))
                    3 -> startActivity(Intent(this, BrowserActivity::class.java))
                    4 -> startActivity(Intent(this, ConnectedServicesActivity::class.java))
                    5 -> startActivity(Intent(this, ToolPlaygroundActivity::class.java))
                    6 -> startActivity(Intent(this, CommanderActivity::class.java))
                    7 -> startActivity(Intent(this, TerminalActivity::class.java))
                    8 -> startActivity(Intent(this, DiagnosticsActivity::class.java))
                    9 -> startActivity(Intent(this, AgyActivity::class.java))
                    10 -> startActivity(Intent(this, PermissionsActivity::class.java))
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
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.bg_chat_user)
            setPadding(36, 24, 36, 24)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = 20
                marginStart = 120
            }
            layoutParams = lp
        }
        messagesContainer.addView(bubble)
    }

    private fun renderStepCard(steps: List<String>, isSuccess: Boolean, finalSummary: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_chat_agent)
            setPadding(36, 32, 36, 32)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                topMargin = 20
                marginEnd = 40
            }
            layoutParams = lp
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            val size = (10 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 16 }
            setBackgroundResource(if (isSuccess) R.drawable.bg_dot else R.drawable.bg_round_glow)
        }
        val tvTitle = TextView(this).apply {
            this.text = if (isSuccess) "Agent Execution ✓" else "Action Completed with Notice"
            setTextColor(if (isSuccess) Color.parseColor("#10B981") else Color.parseColor("#FF7A00"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(dot)
        header.addView(tvTitle)
        card.addView(header)

        for (step in steps) {
            val stepRow = TextView(this).apply {
                this.text = "• $step"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                layoutParams = lp
            }
            card.addView(stepRow)
        }

        val outcome = TextView(this).apply {
            this.text = finalSummary
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            setBackgroundResource(if (isSuccess) R.drawable.bg_step_success else R.drawable.bg_step_progress)
            setPadding(24, 20, 24, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = lp
        }
        card.addView(outcome)

        renderMessageActions(card, finalSummary, null)
        messagesContainer.addView(card)
    }

    private fun renderMessageActions(
        parent: LinearLayout,
        summaryText: String,
        originalPrompt: String? = null
    ) {
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            layoutParams = lp
        }

        // 1. Copy Button
        val btnCopy = TextView(this).apply {
            text = "📋 Copy"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setBackgroundResource(R.drawable.bg_btn_action_pill)
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                val cb = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS Response", summaryText))
                Toast.makeText(this@AgentActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        actionsRow.addView(btnCopy)

        // 2. Regenerate Button
        val btnRegenerate = TextView(this).apply {
            text = "🔄 Regenerate"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setBackgroundResource(R.drawable.bg_btn_action_pill)
            setPadding(20, 10, 20, 10)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 10 }
            layoutParams = lp

            setOnClickListener {
                showRegenerateDialog(originalPrompt ?: lastSubmittedPrompt.ifBlank { summaryText })
            }
        }
        actionsRow.addView(btnRegenerate)

        // 3. Listen Again Button
        val btnListen = TextView(this).apply {
            text = "🔊 Listen"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setBackgroundResource(R.drawable.bg_btn_action_pill)
            setPadding(20, 10, 20, 10)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 10 }
            layoutParams = lp

            setOnClickListener {
                voiceEngine?.speak(summaryText, interrupt = true)
            }
        }
        actionsRow.addView(btnListen)

        // 4. Stop Speaking Button (Instant TTS Stop only - does NOT cancel task)
        val btnStopSpeech = TextView(this).apply {
            text = "■ Stop Speaking"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 11.5f
            setBackgroundResource(R.drawable.bg_btn_action_pill)
            setPadding(20, 10, 20, 10)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 10 }
            layoutParams = lp

            setOnClickListener {
                voiceEngine?.stopSpeaking()
                Toast.makeText(this@AgentActivity, "Speech interrupted", Toast.LENGTH_SHORT).show()
            }
        }
        actionsRow.addView(btnStopSpeech)

        parent.addView(actionsRow)
    }

    private fun showRegenerateDialog(prompt: String) {
        val options = arrayOf(
            "○ Auto (Tri-Tier Cascade)",
            "○ Groq LLaMA 3.3 70B",
            "○ Cloud (Gemini 2.0 Flash)",
            "○ AGY Coding Agent"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Regenerate with:")
            .setItems(options) { _, which ->
                val targetMode = when (which) {
                    0 -> com.pr4nav.jarvis.router.AgentExecutionMode.AUTO
                    1 -> com.pr4nav.jarvis.router.AgentExecutionMode.GROQ_NEEDLE
                    2, 3 -> com.pr4nav.jarvis.router.AgentExecutionMode.CLOUD_NEEDLE
                    else -> com.pr4nav.jarvis.router.AgentExecutionMode.AUTO
                }
                com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.setAgentMode(this, targetMode)
                updateCtx()
                submit(prompt)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        }
    }

    private fun addExecutionStepCard(
        title: String,
        steps: List<String>,
        isSuccess: Boolean,
        finalSummary: String,
        saveToHistory: Boolean = true
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_chat_agent)
            setPadding(36, 32, 36, 32)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                topMargin = 20
                marginEnd = 40
            }
            layoutParams = lp
        }

        // Title Row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            val size = (10 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 16 }
            setBackgroundResource(if (isSuccess) R.drawable.bg_dot else R.drawable.bg_round_glow)
        }
        val tvTitle = TextView(this).apply {
            this.text = title
            setTextColor(if (isSuccess) Color.parseColor("#10B981") else Color.parseColor("#FF7A00"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(dot)
        header.addView(tvTitle)
        card.addView(header)

        // Steps progression
        for (step in steps) {
            val stepRow = TextView(this).apply {
                this.text = "• $step"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                layoutParams = lp
            }
            card.addView(stepRow)
        }

        // Final Outcome Banner
        val outcome = TextView(this).apply {
            this.text = finalSummary
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            setBackgroundResource(if (isSuccess) R.drawable.bg_step_success else R.drawable.bg_step_progress)
            setPadding(24, 20, 24, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = lp
        }
        card.addView(outcome)

        messagesContainer.addView(card)
        renderMessageActions(card, finalSummary, lastSubmittedPrompt)
        scrollToBottom()

        // Persist to active session
        if (saveToHistory && ::currentSession.isInitialized) {
            com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                this,
                currentSession,
                com.pr4nav.jarvis.session.SessionMessage(
                    sender = "agent",
                    text = finalSummary,
                    steps = steps,
                    isSuccess = isSuccess
                )
            )
        }

        // Speak outcome via TTS
        voiceEngine?.speak(finalSummary, interrupt = false)
    }

    @Volatile private var activeTaskId: String? = null

    private fun cancelCurrentTask() {
        isCurrentTaskCancelled = true
        activeTaskId = null
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

    private fun createStreamingCard(initialTitle: String): (String, String, List<String>?, Boolean?) -> Unit {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_chat_agent)
            setPadding(36, 32, 36, 32)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                topMargin = 20
                marginEnd = 40
            }
            layoutParams = lp
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            val size = (10 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 16 }
            setBackgroundResource(R.drawable.bg_round_glow)
        }
        val tvTitle = TextView(this).apply {
            this.text = initialTitle
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(dot)
        header.addView(tvTitle)
        card.addView(header)

        val stepsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(stepsBox)

        val outcome = TextView(this).apply {
            this.text = "Thinking…"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13.5f
            setBackgroundResource(R.drawable.bg_step_progress)
            setPadding(24, 20, 24, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = lp
        }
        card.addView(outcome)

        messagesContainer.addView(card)
        scrollToBottom()

        val textBuffer = StringBuilder()

        return { title: String, chunk: String, steps: List<String>?, isDone: Boolean? ->
            runOnUiThread {
                if (title.isNotEmpty()) {
                    tvTitle.text = title
                }
                if (chunk.isNotEmpty()) {
                    if (outcome.text == "Thinking…" || outcome.text == "Writing response…") {
                        textBuffer.clear()
                    }
                    textBuffer.append(chunk)
                    outcome.text = textBuffer.toString()
                }
                if (steps != null) {
                    stepsBox.removeAllViews()
                    for (step in steps) {
                        val stepRow = TextView(this).apply {
                            this.text = "• $step"
                            setTextColor(Color.parseColor("#94A3B8"))
                            textSize = 12f
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = 6 }
                            layoutParams = lp
                        }
                        stepsBox.addView(stepRow)
                    }
                }
                if (isDone == true) {
                    dot.setBackgroundResource(R.drawable.bg_dot)
                    tvTitle.setTextColor(Color.parseColor("#10B981"))
                    outcome.setBackgroundResource(R.drawable.bg_step_success)
                    renderMessageActions(card, outcome.text.toString(), lastSubmittedPrompt)
                }
                scrollToBottom()
            }
        }
    }

    private fun submit(q: String) {
        if (q.isEmpty()) return
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
                    title = "⚙️ JARVIS Developer & Command Reference",
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
                    title = "📁 Current Working Directory",
                    steps = listOf("Resolved from SessionState"),
                    isSuccess = true,
                    finalSummary = "cwd: ${SessionState.dir}"
                )
            }

            lower.startsWith("ls") -> {
                val p = Fs.resolve(arg.ifBlank { "." })
                val list = Fs.list(p)
                val items = list.take(10).map { (if (it.isDir) "📁 " else "📄 ") + it.name }
                runOnUiThread {
                    if (taskId != null && activeTaskId != taskId) return@runOnUiThread
                    addExecutionStepCard(
                        title = "📁 Directory Listing: $p",
                        steps = items,
                        isSuccess = true,
                        finalSummary = "Found ${list.size} items in $p"
                    )
                }
            }

            lower.startsWith("run ") -> {
                runOnUiThread {
                    if (taskId == null || activeTaskId == taskId) showThinking("Executing Termux Shell…", arg)
                }
                val guardErr = CmdGuard.check(arg)
                if (guardErr != null) {
                    runOnUiThread {
                        if (taskId == null || activeTaskId == taskId) {
                            hideThinking()
                            addExecutionStepCard("⚠️ Command Blocked", listOf(guardErr), false, "Security guard prevented execution.")
                        }
                    }
                    return
                }
                val r = Shell.termux(arg, 60_000)
                runOnUiThread {
                    if (taskId != null && activeTaskId != taskId) return@runOnUiThread
                    hideThinking()
                    addExecutionStepCard(
                        title = "💻 Termux Shell: $arg",
                        steps = listOf("Exit code: ${r.rc}", "Execution time: ${r.ms}ms", "Via: ${r.via}"),
                        isSuccess = r.rc == 0,
                        finalSummary = if (r.out.isNotBlank()) r.out.take(1000) else if (r.err.isNotBlank()) r.err.take(500) else "(no output)"
                    )
                }
            }

            lower == "tools" -> {
                com.pr4nav.jarvis.tools.JarvisToolRegistry.registerAll(this)
                val cat = com.pr4nav.jarvis.tools.JarvisToolRegistry.catalog()
                runOnUiThread {
                    if (taskId != null && activeTaskId != taskId) return@runOnUiThread
                    addExecutionStepCard(
                        title = "🛠️ Registered Canonical Tools",
                        steps = cat.lines().take(12),
                        isSuccess = true,
                        finalSummary = "All canonical tools ready for autonomous execution."
                    )
                }
            }

            else -> {
                // Live Streaming Model Card with Dynamic Statuses (Thinking, Executing, Writing)
                var streamCardUpdater: ((String, String, List<String>?, Boolean?) -> Unit)? = null
                runOnUiThread {
                    if (taskId == null || activeTaskId == taskId) {
                        streamCardUpdater = createStreamingCard("🧠 Thinking…")
                    }
                }

                com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.execute(
                    context = this,
                    rawQuery = q,
                    onStatus = { status ->
                        if (taskId == null || (activeTaskId == taskId && !isCurrentTaskCancelled)) {
                            streamCardUpdater?.invoke(status, "", null, false)
                        }
                    },
                    onChunk = { chunk ->
                        if (taskId == null || (activeTaskId == taskId && !isCurrentTaskCancelled)) {
                            streamCardUpdater?.invoke("", chunk, null, false)
                        }
                    },
                    onResult = { res ->
                        runOnUiThread {
                            if (taskId != null && (activeTaskId != taskId || isCurrentTaskCancelled)) {
                                return@runOnUiThread
                            }

                            val steps = mutableListOf<String>()
                            if (res.thinkingTrace.isNotBlank()) {
                                val traceLines = res.thinkingTrace
                                    .replace("<think>", "")
                                    .replace("</think>", "")
                                    .trim()
                                    .lines()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                steps.addAll(traceLines)
                            } else {
                                steps.add("• Model: ${res.modelName}")
                                steps.add("• Latency: ${res.latencyMs}ms")
                            }

                            streamCardUpdater?.invoke(res.source.badge, res.jarvisResponse.text, steps, true)

                            // Persist to active session
                            if (::currentSession.isInitialized) {
                                com.pr4nav.jarvis.session.JarvisSessionManager.appendMessage(
                                    this,
                                    currentSession,
                                    com.pr4nav.jarvis.session.SessionMessage(
                                        sender = "agent",
                                        text = "${res.source.badge}\n${res.jarvisResponse.text}",
                                        steps = steps,
                                        isSuccess = res.handled
                                    )
                                )
                            }

                            // Speak response via Kokoro-82M TTS only if not cancelled
                            if (taskId == null || (activeTaskId == taskId && !isCurrentTaskCancelled)) {
                                voiceEngine?.speak(res.jarvisResponse.speechText, interrupt = false)
                            }
                            updateCtx()
                        }
                    }
                )
            }
        }
    }
}
