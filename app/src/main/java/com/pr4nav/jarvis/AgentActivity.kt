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

    private var voiceEngine: JarvisVoiceEngine? = null

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

        voiceEngine = JarvisVoiceEngine(this)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

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

        updateCtx()
    }

    override fun onResume() {
        super.onResume()
        updateCtx()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine?.destroy()
    }

    private fun updateCtx() {
        val termuxState = if (Shell.termuxReachable()) "UP" else "DOWN"
        agentCtx.text = "cwd: ${SessionState.dir} · storage: ${Fs.accessLevel} · termux: $termuxState"
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
                    0 -> startActivity(Intent(this, BrowserActivity::class.java))
                    1 -> startActivity(Intent(this, ConnectedServicesActivity::class.java))
                    2 -> startActivity(Intent(this, ToolPlaygroundActivity::class.java))
                    3 -> startActivity(Intent(this, CommanderActivity::class.java))
                    4 -> startActivity(Intent(this, TerminalActivity::class.java))
                    5 -> startActivity(Intent(this, DiagnosticsActivity::class.java))
                    6 -> startActivity(Intent(this, AgyActivity::class.java))
                    7 -> startActivity(Intent(this, PermissionsActivity::class.java))
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

    private fun addUserMessage(text: String) {
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
        scrollToBottom()
    }

    private fun addExecutionStepCard(title: String, steps: List<String>, isSuccess: Boolean, finalSummary: String) {
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
        scrollToBottom()

        // Speak outcome via TTS
        voiceEngine?.speak(finalSummary, interrupt = false)
    }

    private fun submit(q: String) {
        if (q.isEmpty()) return
        input.setText("")
        addUserMessage(q)
        showThinking("Analyzing intent…", "Evaluating deterministic tools & SLM")

        thread {
            try {
                handle(q)
            } catch (e: Exception) {
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

    private fun handle(q: String) {
        // Step 1: Route through JarvisIntentRouter
        runOnUiThread {
            showThinking("Resolving action…", "Searching canonical capabilities")
        }

        val routed = com.pr4nav.jarvis.router.JarvisIntentRouter.routeAndExecute(this, q) { res ->
            runOnUiThread {
                hideThinking()
                val steps = mutableListOf<String>()
                steps.add("Intent parsed: \"$q\"")
                if (res.capabilities.isNotEmpty()) {
                    steps.add("Capabilities triggered: ${res.capabilities.joinToString { "${it.icon} ${it.label}" }}")
                }

                addExecutionStepCard(
                    title = if (res.matched) "Action Completed ✓" else "Action Completed with Warnings",
                    steps = steps,
                    isSuccess = res.matched,
                    finalSummary = res.executionSummary
                )
                updateCtx()
            }
        }

        if (routed) return

        // Step 2: Handle Built-in CLI & Agent Commands
        val lower = q.lowercase()
        val arg = q.split(" ", limit = 2).getOrNull(1)?.trim() ?: ""

        when {
            lower == "help" -> runOnUiThread {
                hideThinking()
                addExecutionStepCard(
                    title = "JARVIS Help & Commands",
                    steps = listOf(
                        "Natural Language: \"call Akhil\", \"open Spotify\", \"take screenshot\"",
                        "System: pwd, ls [path], read <path>, write <path> <text>",
                        "Shell: run <cmd> (executes in Termux)",
                        "Tools: tools (lists all 23+ canonical tools)"
                    ),
                    isSuccess = true,
                    finalSummary = "Available commands loaded."
                )
            }

            lower == "pwd" -> runOnUiThread {
                hideThinking()
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
                val items = list.take(10).map { (if (it.isDir) "📁 " else "📄 ") + it.name }
                runOnUiThread {
                    hideThinking()
                    addExecutionStepCard(
                        title = "Directory Listing: $p",
                        steps = items,
                        isSuccess = true,
                        finalSummary = "Found ${list.size} items in $p"
                    )
                }
            }

            lower.startsWith("run ") -> {
                runOnUiThread { showThinking("Executing Termux Shell…", arg) }
                CmdGuard.check(arg)?.let { err ->
                    runOnUiThread {
                        hideThinking()
                        addExecutionStepCard("Command Blocked", listOf(err), false, "Security guard prevented execution.")
                    }
                    return
                }
                val r = Shell.termux(arg, 60_000)
                runOnUiThread {
                    hideThinking()
                    addExecutionStepCard(
                        title = "Termux Shell: $arg",
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
                    hideThinking()
                    addExecutionStepCard(
                        title = "Registered Canonical Tools",
                        steps = cat.lines().take(12),
                        isSuccess = true,
                        finalSummary = "All canonical tools ready for autonomous execution."
                    )
                }
            }

            else -> {
                // Tier 3: Escalate to Cloud Intelligence (Gemini API or AGY)
                runOnUiThread {
                    showThinking("Consulting Cloud Intelligence…", "Querying Gemini reasoning engine")
                }
                com.pr4nav.jarvis.llm.GeminiCloudLLM.generate(
                    context = this,
                    prompt = q,
                    onSuccess = { answer ->
                        runOnUiThread {
                            hideThinking()
                            addExecutionStepCard(
                                title = "JARVIS Cloud Intelligence",
                                steps = listOf("Escalated to Gemini Generative AI", "Reasoning generated"),
                                isSuccess = true,
                                finalSummary = answer
                            )
                        }
                    },
                    onError = { err ->
                        runOnUiThread {
                            hideThinking()
                            val fallbackMsg = if (com.pr4nav.jarvis.llm.GeminiCloudLLM.getApiKey(this).isEmpty()) {
                                "I couldn't resolve a local action for \"$q\". To enable conversational answers and general intelligence, add your Gemini API key in Connected Services."
                            } else {
                                "Cloud query failed: $err. Try asking me to open an app, make a call, or play music!"
                            }
                            addExecutionStepCard(
                                title = "Autonomous Reasoning Notice",
                                steps = listOf("No deterministic tool match", "Cloud query unfulfilled: $err"),
                                isSuccess = false,
                                finalSummary = fallbackMsg
                            )
                        }
                    }
                )
            }
        }
    }
}
