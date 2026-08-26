package com.pr4nav.jarvis

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.opencode.InMemoryKvStore
import com.pr4nav.jarvis.opencode.OcResult
import com.pr4nav.jarvis.opencode.OpenCode
import com.pr4nav.jarvis.opencode.OpenCodeClient
import com.pr4nav.jarvis.opencode.OpenCodeConfig
import com.pr4nav.jarvis.opencode.OpenCodeSessionStore
import com.pr4nav.jarvis.opencode.PrefsKvStore
import com.pr4nav.jarvis.opencode.json.OcEvent
import com.pr4nav.jarvis.opencode.json.OcMessageInfo
import com.pr4nav.jarvis.opencode.json.OcModelRef
import com.pr4nav.jarvis.opencode.json.OcPart
import com.pr4nav.jarvis.opencode.json.OcProject

class OpenCodeActivity : AppCompatActivity() {

    private lateinit var serverDot: View
    private lateinit var serverLabel: TextView
    private lateinit var versionLabel: TextView
    private lateinit var inputServerUrl: EditText
    private lateinit var projectLabel: TextView
    private lateinit var sessionTitle: TextView
    private lateinit var sessionStatus: TextView
    private lateinit var inputPrompt: EditText
    private lateinit var queueBadge: TextView
    private lateinit var toolsHeader: TextView
    private lateinit var diffView: TextView
    private lateinit var listSessions: ListView
    private lateinit var listMessages: ListView
    private lateinit var listTools: ListView
    private lateinit var spinnerAgent: Spinner
    private lateinit var spinnerModel: Spinner
    private lateinit var permBanner: View
    private lateinit var permText: TextView
    private lateinit var questionBanner: View
    private lateinit var questionText: TextView
    private lateinit var questionOptions: ViewGroup

    private lateinit var openCode: OpenCode
    private var currentProjectDir: String? = null
    private var pendingPermId: String? = null
    private var pendingPermDir: String? = null
    private var pendingQuestionId: String? = null
    private var pendingQuestionDir: String? = null

    private val sessionsData = mutableListOf<SessionRow>()
    private val messagesData = mutableListOf<MessageRow>()
    private val toolsData = mutableListOf<ToolRow>()

    private lateinit var sessionsAdapter: SessionsAdapter
    private lateinit var messagesAdapter: MessagesAdapter
    private lateinit var toolsAdapter: ToolsAdapter

    private var agentNames = listOf("build", "plan")
    private var modelChoices = listOf<ModelChoice>(ModelChoice("— default —", null))

    data class SessionRow(val id: String, val title: String, val dir: String, val busy: Boolean, val unread: Int, val isCurrent: Boolean)
    data class MessageRow(val info: OcMessageInfo, val parts: List<OcPart>)
    data class ToolRow(val name: String, val status: String, val detail: String, val time: String)
    data class ModelChoice(val label: String, val ref: OcModelRef?)

    private val eventListener = object : OpenCode.EventListener {
        override fun onEvent(event: OcEvent) {
            runOnUiThread { handleEvent(event) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_opencode)
        bindViews()
        initOpenCode()
        setupAdapters()
        setupClicks()
        refreshServerStatus()
        loadProjects()
        loadSessions()
        loadAgentsAndModels()
    }

    override fun onResume() {
        super.onResume()
        openCode.setEventListener(eventListener)
    }

    override fun onPause() {
        openCode.setEventListener(null)
        super.onPause()
    }

    private fun bindViews() {
        serverDot = findViewById(R.id.oc_server_dot)
        serverLabel = findViewById(R.id.oc_server_label)
        versionLabel = findViewById(R.id.oc_version_label)
        inputServerUrl = findViewById(R.id.input_server_url)
        projectLabel = findViewById(R.id.oc_project_label)
        sessionTitle = findViewById(R.id.oc_session_title)
        sessionStatus = findViewById(R.id.oc_session_status)
        inputPrompt = findViewById(R.id.input_oc_prompt)
        queueBadge = findViewById(R.id.oc_queue_badge)
        toolsHeader = findViewById(R.id.oc_tools_header)
        diffView = findViewById(R.id.oc_diff_view)
        listSessions = findViewById(R.id.list_oc_sessions)
        listMessages = findViewById(R.id.list_oc_messages)
        listTools = findViewById(R.id.list_oc_tools)
        spinnerAgent = findViewById(R.id.spinner_oc_agent)
        spinnerModel = findViewById(R.id.spinner_oc_model)
        permBanner = findViewById(R.id.oc_permission_banner)
        permText = findViewById(R.id.oc_permission_text)
        questionBanner = findViewById(R.id.oc_question_banner)
        questionText = findViewById(R.id.oc_question_text)
        questionOptions = findViewById(R.id.oc_question_options)
    }

    private fun initOpenCode() {
        try {
            val kv = try { PrefsKvStore(this) } catch (_: Exception) { InMemoryKvStore() }
            if (!OpenCode.isInitialized()) {
                val cfg = OpenCodeConfig()
                inputServerUrl.setText(cfg.baseUrl)
                OpenCode.init(this, kv, cfg)
            }
            openCode = OpenCode.get()
            openCode.setEventListener(eventListener)
            val savedBase = kv.getString(OpenCodeSessionStore.KEY_SERVER_BASE_URL)
            if (savedBase != null) inputServerUrl.setText(savedBase)
            currentProjectDir = savedBase?.let { null } ?: openCode.projects.currentDirectory()
            if (currentProjectDir != null) projectLabel.text = currentProjectDir
        } catch (e: Exception) {
            Toast.makeText(this, "OpenCode init failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupAdapters() {
        sessionsAdapter = SessionsAdapter()
        listSessions.adapter = sessionsAdapter
        listSessions.setOnItemClickListener { _, _, pos, _ ->
            val row = sessionsData[pos]
            switchSession(row.id)
        }
        listSessions.setOnItemLongClickListener { _, _, pos, _ ->
            showSessionActions(sessionsData[pos])
            true
        }
        messagesAdapter = MessagesAdapter()
        listMessages.adapter = messagesAdapter
        toolsAdapter = ToolsAdapter()
        listTools.adapter = toolsAdapter
    }

    private fun setupClicks() {
        findViewById<View>(R.id.btn_oc_connect).setOnClickListener { doConnect() }
        findViewById<View>(R.id.btn_oc_reconnect).setOnClickListener { refreshServerStatus() }
        findViewById<View>(R.id.btn_oc_projects).setOnClickListener { showProjectPicker() }
        findViewById<View>(R.id.btn_oc_refresh_projects).setOnClickListener { loadProjects() }
        findViewById<View>(R.id.btn_oc_new_session).setOnClickListener { createNewSession() }
        findViewById<View>(R.id.btn_oc_abort).setOnClickListener { abortCurrent() }
        findViewById<View>(R.id.btn_oc_fork).setOnClickListener { forkCurrent() }
        findViewById<View>(R.id.btn_oc_rename).setOnClickListener { renameCurrent() }
        findViewById<View>(R.id.btn_oc_refresh_sessions).setOnClickListener { loadSessions() }
        findViewById<View>(R.id.btn_oc_send).setOnClickListener { sendPrompt() }
        findViewById<View>(R.id.btn_perm_once).setOnClickListener { replyPerm("once") }
        findViewById<View>(R.id.btn_perm_always).setOnClickListener { replyPerm("always") }
        findViewById<View>(R.id.btn_perm_reject).setOnClickListener { replyPerm("reject") }
        findViewById<View>(R.id.btn_oc_open_web).setOnClickListener { openWebUi() }

        spinnerAgent.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (pos in agentNames.indices) openCode.agents.selectAgent(agentNames[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val c = modelChoices.getOrNull(pos) ?: return
                if (c.ref != null) openCode.models.selectModel(c.ref)
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun doConnect() {
        val url = inputServerUrl.text.toString().trim().ifBlank { "http://127.0.0.1:4096" }
        serverLabel.text = "Connecting to $url…"
        Thread {
            val res = openCode.connect(url)
            runOnUiThread {
                if (res.isSuccess) {
                    Toast.makeText(this, "Connected to ${res.state?.baseUrl}", Toast.LENGTH_SHORT).show()
                    refreshServerStatus()
                    loadProjects()
                    loadSessions()
                } else {
                    Toast.makeText(this, "Connect failed: ${res.error?.message}", Toast.LENGTH_LONG).show()
                    serverLabel.text = "Disconnected — ${res.error?.code}"
                    serverDot.setBackgroundColor(0xFFF85149.toInt())
                }
            }
        }.start()
    }

    private fun openWebUi() {
        val url = inputServerUrl.text.toString().trim().ifBlank { openCode.config.baseUrl }
        // default = server's own web UI inside WebView (no Chrome). Long-press logic for custom html is in the web page itself.
        startActivity(android.content.Intent(this, OpenCodeWebActivity::class.java).putExtra("baseUrl", url))
    }

    private fun refreshServerStatus() {
        Thread {
            val st = openCode.process.current
            val health = openCode.client.health()
            runOnUiThread {
                if (health is OcResult.Ok && health.value.healthy) {
                    serverDot.setBackgroundColor(0xFF3FB950.toInt())
                    serverLabel.text = st?.baseUrl ?: openCode.config.baseUrl
                    versionLabel.text = health.value.version ?: ""
                    sessionStatus.text = if (openCode.events.connected) "● live" else "○ reconnecting"
                } else {
                    serverDot.setBackgroundColor(0xFFF85149.toInt())
                    val err = (health as? OcResult.Err)?.error?.code?.name ?: "unavailable"
                    // hint web fallback when API reports NOT_FOUND but HTML at / would still work
                    val hint = if (err == "NOT_FOUND") " — try Web UI ↗" else ""
                    serverLabel.text = "Server $err$hint — tap Connect"
                    versionLabel.text = ""
                }
            }
        }.start()
    }

    private fun loadProjects() {
        Thread {
            val res = openCode.projects.projects()
            runOnUiThread {
                if (res is OcResult.Ok) {
                    val list = res.value
                    if (list.isNotEmpty() && currentProjectDir == null) {
                        currentProjectDir = list.first().worktree
                        projectLabel.text = currentProjectDir ?: "—"
                        loadSessions()
                    }
                }
            }
        }.start()
        val dir = openCode.projects.currentDirectory()
        if (dir != null) {
            currentProjectDir = dir
            projectLabel.text = dir
        }
    }

    private fun showProjectPicker() {
        Thread {
            val res = openCode.projects.projects()
            runOnUiThread {
                val projects: List<OcProject> = (res as? OcResult.Ok)?.value ?: emptyList()
                if (projects.isEmpty()) {
                    inputProjectManually()
                    return@runOnUiThread
                }
                val items = projects.map { p -> "${p.name ?: p.id.take(12)} — ${p.worktree ?: ""}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select project")
                    .setItems(items) { _, which ->
                        val p = projects[which]
                        p.worktree?.let {
                            currentProjectDir = it
                            projectLabel.text = it
                            openCode.projects.selectDirectory(it)
                            openCode.events.directoryFilter = setOf(it)
                            loadSessions()
                        }
                    }
                    .setNegativeButton("Enter path") { _, _ -> inputProjectManually() }
                    .show()
            }
        }.start()
    }

    private fun inputProjectManually() {
        val et = EditText(this)
        et.hint = "/data/data/com.termux/files/home/… or /storage/…"
        et.setText(currentProjectDir ?: "")
        AlertDialog.Builder(this).setTitle("Project directory").setView(et)
            .setPositiveButton("Use") { _, _ ->
                val dir = et.text.toString().trim()
                if (dir.isNotBlank()) {
                    currentProjectDir = dir
                    projectLabel.text = dir
                    openCode.projects.selectDirectory(dir)
                    openCode.events.directoryFilter = setOf(dir)
                    loadSessions()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun loadSessions() {
        Thread {
            openCode.sessions.refreshFromServer(currentProjectDir)
            val list = openCode.sessions.list()
            runOnUiThread {
                sessionsData.clear()
                val cur = openCode.sessions.currentSessionId
                list.forEach { e ->
                    sessionsData.add(SessionRow(e.sessionId, e.title ?: e.label ?: e.sessionId.take(16), e.directory, e.busy, e.unread, e.sessionId == cur))
                }
                sessionsAdapter.notifyDataSetChanged()
                updateSessionHeader()
                loadMessagesForCurrent()
                refreshTools()
            }
        }.start()
    }

    private fun switchSession(id: String) {
        openCode.sessions.setCurrent(id)
        loadSessions()
        refreshServerStatus()
    }

    private fun createNewSession() {
        val dir = currentProjectDir
        if (dir.isNullOrBlank()) { Toast.makeText(this, "Pick a project first", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val res = openCode.sessions.createSession(dir)
            runOnUiThread {
                if (res is OcResult.Ok) {
                    Toast.makeText(this, "Session ${res.value.sessionId.take(12)} created", Toast.LENGTH_SHORT).show()
                    loadSessions()
                } else {
                    Toast.makeText(this, "Create failed: ${(res as OcResult.Err).error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun abortCurrent() {
        val id = openCode.sessions.currentSessionId ?: return
        Thread {
            val res = openCode.sessions.abort(id)
            runOnUiThread {
                Toast.makeText(this, if (res.isOk) "Aborted" else "Abort failed: ${(res as OcResult.Err).error.message}", Toast.LENGTH_SHORT).show()
                loadSessions()
            }
        }.start()
    }

    private fun forkCurrent() {
        val id = openCode.sessions.currentSessionId ?: return
        Thread {
            val res = openCode.sessions.fork(id)
            runOnUiThread {
                if (res is OcResult.Ok) {
                    Toast.makeText(this, "Forked ${res.value.sessionId.take(12)}", Toast.LENGTH_SHORT).show()
                    loadSessions()
                } else Toast.makeText(this, "Fork failed", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun renameCurrent() {
        val id = openCode.sessions.currentSessionId ?: return
        val et = EditText(this)
        et.hint = "New title"
        AlertDialog.Builder(this).setTitle("Rename session").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isBlank()) return@setPositiveButton
                Thread {
                    val res = openCode.sessions.rename(id, t)
                    runOnUiThread {
                        if (res.isOk) loadSessions() else Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSessionActions(row: SessionRow) {
        val opts = arrayOf("Switch to this", "Fork", "Rename", "Abort", "Delete")
        AlertDialog.Builder(this).setTitle(row.title).setItems(opts) { _, w ->
            when (w) {
                0 -> switchSession(row.id)
                1 -> Thread { openCode.sessions.fork(row.id); runOnUiThread { loadSessions() } }.start()
                2 -> { openCode.sessions.setCurrent(row.id); renameCurrent() }
                3 -> Thread { openCode.sessions.abort(row.id); runOnUiThread { loadSessions() } }.start()
                4 -> AlertDialog.Builder(this).setMessage("Delete ${row.title}?").setPositiveButton("Delete") { _, _ ->
                    Thread { openCode.sessions.archive(row.id); runOnUiThread { loadSessions() } }.start()
                }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun loadMessagesForCurrent() {
        val id = openCode.sessions.currentSessionId ?: run { messagesData.clear(); messagesAdapter.notifyDataSetChanged(); return }
        Thread {
            val res = openCode.sessions.backfillMessages(id, 60)
            runOnUiThread {
                messagesData.clear()
                if (res is OcResult.Ok) {
                    res.value.forEach { (info, parts) -> messagesData.add(MessageRow(info, parts)) }
                }
                messagesAdapter.notifyDataSetChanged()
                if (messagesData.isNotEmpty()) listMessages.setSelection(messagesData.size - 1)
                updateSessionHeader()
            }
        }.start()
    }

    private fun refreshTools() {
        val id = openCode.sessions.currentSessionId
        toolsData.clear()
        if (id != null) {
            val calls = openCode.tools.callsForSession(id)
            calls.forEach { c ->
                val elapsed = if (c.completedAtMs > 0 && c.startedAtMs > 0) "${(c.completedAtMs - c.startedAtMs) / 1000}s" else ""
                toolsData.add(ToolRow(c.tool, c.status.name.lowercase(), c.title ?: c.filePath ?: "", elapsed))
            }
            if (calls.isEmpty() && openCode.tools.allCalls().isNotEmpty()) {
                openCode.tools.allCalls().takeLast(6).forEach { c ->
                    toolsData.add(ToolRow(c.tool, c.status.name.lowercase(), c.title ?: "", ""))
                }
            }
            toolsHeader.text = if (toolsData.isEmpty()) "Tools — idle" else "Tools — ${toolsData.size} calls"
            val summary = openCode.tools.changedFiles.summary()
            diffView.text = if (summary.isBlank()) "No files changed yet" else summary
            diffView.visibility = View.VISIBLE
        } else {
            toolsHeader.text = "Tools — no session"
            diffView.visibility = View.GONE
        }
        toolsAdapter.notifyDataSetChanged()
    }

    private fun loadAgentsAndModels() {
        Thread {
            val agentsRes = openCode.agents.agents()
            val providersRes = openCode.models.catalog()
            runOnUiThread {
                if (agentsRes is OcResult.Ok) {
                    agentNames = agentsRes.value.filter { !it.hidden }.map { it.name }.ifEmpty { listOf("build", "plan") }
                    spinnerAgent.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, agentNames)
                    val cur = openCode.agents.current
                    val idx = agentNames.indexOf(cur).takeIf { it >= 0 } ?: 0
                    spinnerAgent.setSelection(idx)
                }
                if (providersRes is OcResult.Ok) {
                    val choices = mutableListOf(ModelChoice("— default —", null))
                    providersRes.value.providers.forEach { p ->
                        p.models.forEach { m ->
                            choices.add(ModelChoice("${p.id}/${m.id}", OcModelRef(p.id, m.id)))
                            m.variants.forEach { v -> choices.add(ModelChoice("${p.id}/${m.id}:$v", OcModelRef(p.id, m.id, v))) }
                        }
                    }
                    modelChoices = choices
                    spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map { it.label })
                    val cur = openCode.models.current
                    val idx = choices.indexOfFirst { it.ref?.providerID == cur?.providerID && it.ref?.modelID == cur?.modelID }.takeIf { it >= 0 } ?: 0
                    spinnerModel.setSelection(idx)
                }
            }
        }.start()
    }

    private fun sendPrompt() {
        val text = inputPrompt.text.toString().trim()
        if (text.isBlank()) return
        val cur = openCode.sessions.currentSessionId
        if (cur == null) {
            Toast.makeText(this, "Create a session first", Toast.LENGTH_SHORT).show(); return
        }
        inputPrompt.setText("")
        val entry = openCode.sessions.get(cur)
        Thread {
            val res = openCode.sessions.sendPrompt(cur, listOf(OpenCodeClient.PromptPart.Text(text)), openCode.agents.current, openCode.models.current)
            runOnUiThread {
                when (res) {
                    is com.pr4nav.jarvis.opencode.OpenCodeSessionManager.PromptSubmit.Started -> {
                        sessionStatus.text = "busy"
                        sessionStatus.setBackgroundColor(0xFFF0883E.toInt())
                    }
                    is com.pr4nav.jarvis.opencode.OpenCodeSessionManager.PromptSubmit.Queued -> {
                        queueBadge.text = "queued #${res.position}"
                        queueBadge.visibility = View.VISIBLE
                    }
                    is com.pr4nav.jarvis.opencode.OpenCodeSessionManager.PromptSubmit.Failed -> {
                        Toast.makeText(this, res.error.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun replyPerm(decision: String) {
        val id = pendingPermId ?: return
        val dir = pendingPermDir
        permBanner.visibility = View.GONE
        val d = when (decision) {
            "always" -> OpenCodeClient.PermissionDecision.ALWAYS
            "reject" -> OpenCodeClient.PermissionDecision.REJECT
            else -> OpenCodeClient.PermissionDecision.ONCE
        }
        Thread {
            val res = openCode.permissions.respondToPermission(id, d, dir)
            runOnUiThread {
                if (res is OcResult.Err) Toast.makeText(this, res.error.message, Toast.LENGTH_SHORT).show()
            }
        }.start()
        pendingPermId = null
    }

    private fun handleEvent(event: OcEvent) {
        when (event) {
            is OcEvent.SessionCreated, is OcEvent.SessionUpdated -> loadSessions()
            is OcEvent.SessionIdle -> {
                if (event.sessionId == openCode.sessions.currentSessionId) {
                    sessionStatus.text = "idle"
                    sessionStatus.setBackgroundColor(0xFF21262D.toInt())
                    queueBadge.visibility = View.GONE
                    loadMessagesForCurrent()
                    refreshTools()
                } else {
                    loadSessions()
                }
                refreshServerStatus()
            }
            is OcEvent.SessionError -> {
                if (event.sessionId == openCode.sessions.currentSessionId) {
                    sessionStatus.text = "error"
                    sessionStatus.setBackgroundColor(0xFFF85149.toInt())
                    loadMessagesForCurrent()
                }
            }
            is OcEvent.PartUpdated -> {
                if (event.part.sessionId == openCode.sessions.currentSessionId) {
                    if (event.part.type == "text" || event.part.type == "reasoning") loadMessagesForCurrent()
                    else refreshTools()
                }
            }
            is OcEvent.PartDelta -> {
                if (event.sessionId == openCode.sessions.currentSessionId) {
                    sessionStatus.text = "streaming…"
                }
            }
            is OcEvent.PermissionAsked -> {
                pendingPermId = event.request.requestId
                pendingPermDir = currentProjectDir
                permText.text = event.request.patterns.firstOrNull()?.let { "${event.request.type ?: "permission"}: $it" }
                    ?: (event.request.title ?: "Permission requested")
                permBanner.visibility = View.VISIBLE
            }
            is OcEvent.PermissionReplied -> {
                if (event.requestId == pendingPermId) permBanner.visibility = View.GONE
            }
            is OcEvent.QuestionAsked -> {
                pendingQuestionId = event.request.requestId
                pendingQuestionDir = currentProjectDir
                val q = event.request.questions.firstOrNull()
                questionText.text = q?.question ?: "Question"
                questionOptions.removeAllViews()
                q?.options?.forEach { opt ->
                    val b = Button(this)
                    b.text = opt.label
                    b.textSize = 11f
                    b.setOnClickListener {
                        answerQuestion(pendingQuestionId!!, listOf(listOf(opt.label)))
                    }
                    questionOptions.addView(b)
                }
                val et = EditText(this)
                et.hint = "Or type custom answer…"
                et.textSize = 12f
                val send = Button(this)
                send.text = "Send answer"
                send.setOnClickListener {
                    val txt = et.text.toString().trim()
                    if (txt.isNotBlank()) answerQuestion(pendingQuestionId!!, listOf(listOf(txt)))
                }
                questionOptions.addView(et)
                questionOptions.addView(send)
                questionBanner.visibility = View.VISIBLE
            }
            is OcEvent.QuestionReplied, is OcEvent.QuestionRejected -> {
                questionBanner.visibility = View.GONE
                pendingQuestionId = null
            }
            is OcEvent.SessionDiff -> {
                diffView.text = event.files.joinToString(", ") { "${it.file} (+${it.additions}/-${it.deletions})" }
                diffView.visibility = View.VISIBLE
            }
            else -> {}
        }
    }

    private fun answerQuestion(requestId: String, answers: List<List<String>>) {
        questionBanner.visibility = View.GONE
        Thread {
            val res = openCode.permissions.respondToQuestion(requestId, answers, pendingQuestionDir)
            runOnUiThread {
                if (res is OcResult.Err) Toast.makeText(this, res.error.message, Toast.LENGTH_SHORT).show()
            }
        }.start()
        pendingQuestionId = null
    }

    private fun updateSessionHeader() {
        val cur = openCode.sessions.current()
        if (cur == null) {
            sessionTitle.text = "No session"
            sessionStatus.text = "idle"
            sessionStatus.setBackgroundColor(0xFF21262D.toInt())
        } else {
            sessionTitle.text = cur.title ?: cur.sessionId.take(16)
            val busy = cur.busy
            if (busy) {
                sessionStatus.text = "busy"
                sessionStatus.setBackgroundColor(0xFFF0883E.toInt())
            } else {
                sessionStatus.text = if (cur.unread > 0) "idle • ${cur.unread} new" else "idle"
                sessionStatus.setBackgroundColor(0xFF21262D.toInt())
            }
            val q = cur.queue.size
            if (q > 0) { queueBadge.text = "queued $q"; queueBadge.visibility = View.VISIBLE } else queueBadge.visibility = View.GONE
        }
    }

    inner class SessionsAdapter : BaseAdapter() {
        override fun getCount() = sessionsData.size
        override fun getItem(p: Int) = sessionsData[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(parent.context).inflate(R.layout.item_oc_session, parent, false)
            val r = sessionsData[pos]
            v.findViewById<TextView>(R.id.txt_session_title).text = r.title
            v.findViewById<TextView>(R.id.txt_session_id).text = r.id
            v.findViewById<TextView>(R.id.txt_session_dir).text = r.dir
            val dot = v.findViewById<View>(R.id.dot_status)
            dot.setBackgroundColor(if (r.busy) 0xFFF0883E.toInt() else 0xFF3FB950.toInt())
            val unread = v.findViewById<TextView>(R.id.txt_unread)
            if (r.unread > 0 && !r.isCurrent) { unread.text = r.unread.toString(); unread.visibility = View.VISIBLE } else unread.visibility = View.GONE
            v.setBackgroundColor(if (r.isCurrent) 0xFF21262D.toInt() else 0x00000000)
            return v
        }
    }

    inner class MessagesAdapter : BaseAdapter() {
        override fun getCount() = messagesData.size
        override fun getItem(p: Int) = messagesData[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(parent.context).inflate(R.layout.item_oc_message, parent, false)
            val row = messagesData[pos]
            val role = v.findViewById<TextView>(R.id.txt_role)
            role.text = (row.info.role ?: "unknown").uppercase()
            role.setBackgroundColor(when (row.info.role) { "user" -> 0xFF1F6FEB.toInt(); "assistant" -> 0xFF238636.toInt(); else -> 0xFF21262D.toInt() })
            v.findViewById<TextView>(R.id.txt_agent_model).text = listOfNotNull(row.info.agent, row.info.providerID?.let { "${row.info.providerID}/${row.info.modelID}" }).joinToString(" · ")
            val body = row.parts.filter { it.type == "text" && !it.synthetic }.joinToString("\n\n") { it.text ?: "" }
            v.findViewById<TextView>(R.id.txt_message_body).text = body.ifBlank { "(no text — ${row.parts.size} parts)" }
            val reasoning = v.findViewById<TextView>(R.id.txt_reasoning)
            val think = row.parts.filter { it.type == "reasoning" }.joinToString("\n") { it.text ?: "" }
            if (think.isNotBlank()) { reasoning.text = think; reasoning.visibility = View.VISIBLE } else reasoning.visibility = View.GONE
            return v
        }
    }

    inner class ToolsAdapter : BaseAdapter() {
        override fun getCount() = toolsData.size
        override fun getItem(p: Int) = toolsData[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(parent.context).inflate(R.layout.item_oc_tool, parent, false)
            val r = toolsData[pos]
            v.findViewById<TextView>(R.id.txt_tool_name).text = r.name
            val st = v.findViewById<TextView>(R.id.txt_tool_status)
            st.text = r.status
            st.setBackgroundColor(when (r.status) { "running" -> 0xFFF0883E.toInt(); "completed" -> 0xFF3FB950.toInt(); "error" -> 0xFFF85149.toInt(); else -> 0xFF21262D.toInt() })
            v.findViewById<TextView>(R.id.txt_tool_detail).text = r.detail
            v.findViewById<TextView>(R.id.txt_tool_time).text = r.time
            return v
        }
    }
}
