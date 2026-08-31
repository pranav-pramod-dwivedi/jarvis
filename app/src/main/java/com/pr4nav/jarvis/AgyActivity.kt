package com.pr4nav.jarvis

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.agy.AgyClient
import com.pr4nav.jarvis.agy.AgyConfig
import com.pr4nav.jarvis.agy.AgyProcessManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgyActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_FAST_MODEL = "Gemini 3.5 Flash (Low)"
    }

    private lateinit var dotStatus: View
    private lateinit var labelServerStatus: TextView
    private lateinit var btnServerToggle: Button
    private lateinit var btnWebConsole: Button
    private lateinit var btnNewChat: Button
    private lateinit var btnClearOutput: Button
    private lateinit var outputScroller: ScrollView
    private lateinit var outputView: TextView
    private lateinit var inputPrompt: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAbort: Button

    private var isFirstTurn = true
    private val client = AgyClient()
    private val processManager = AgyProcessManager(client)
    private var activeStreamHandle: AgyClient.StreamHandle? = null
    private var isServerOnline = false
    private var isBusy = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            processManager.checkStatus()
            mainHandler.postDelayed(this, 10_000)
        }
    }

    private lateinit var voiceEngine: com.pr4nav.jarvis.voice.JarvisVoiceEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agy)

        voiceEngine = com.pr4nav.jarvis.voice.JarvisVoiceEngine(this)
        com.pr4nav.jarvis.automation.JarvisAutomationEngine.init(this)

        bindViews()
        setupListeners()
        setupChips()

        if (!TermuxBridge.hasPermission() && android.os.Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(TermuxBridge.PERM), 8821)
        }

        appendBanner("⚡ Antigravity (AGY) Console initialized.\nConnecting to AGY daemon on 127.0.0.1:5050...\n")
    }

    override fun onResume() {
        super.onResume()
        processManager.onStatusChanged = { running, detail ->
            runOnUiThread { updateServerState(running, detail) }
        }
        processManager.checkStatus()
        mainHandler.postDelayed(pollRunnable, 10_000)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(pollRunnable)
        processManager.onStatusChanged = null
    }

    private fun bindViews() {
        dotStatus = findViewById(R.id.dot_status)
        labelServerStatus = findViewById(R.id.label_server_status)
        btnServerToggle = findViewById(R.id.btn_server_toggle)
        btnWebConsole = findViewById(R.id.btn_web_console)
        btnNewChat = findViewById(R.id.btn_new_chat)
        btnClearOutput = findViewById(R.id.btn_clear_output)
        outputScroller = findViewById(R.id.output_scroller)
        outputView = findViewById(R.id.output_view)
        inputPrompt = findViewById(R.id.input_prompt)
        btnSend = findViewById(R.id.btn_send)
        btnAbort = findViewById(R.id.btn_abort)
    }

    private fun setupListeners() {
        btnServerToggle.setOnClickListener {
            if (isServerOnline) {
                appendSystem("Stopping AGY serve daemon...")
                processManager.stopServer {
                    Toast.makeText(this, "AGY server stopped", Toast.LENGTH_SHORT).show()
                }
            } else {
                appendSystem("Starting AGY serve daemon on Termux...")
                updateServerState(false, "Starting on Termux...")
                processManager.startServer { success, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnWebConsole.setOnClickListener {
            startActivity(Intent(this, AgyWebActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btn_back)?.setOnClickListener { finish() }

        findViewById<Button>(R.id.btn_connect_services)?.setOnClickListener {
            startActivity(Intent(this, ConnectedServicesActivity::class.java))
        }

        btnNewChat.setOnClickListener {
            isFirstTurn = true
            outputView.text = ""
            appendSystem("⚡ Started new chat session. Context reset.\n")
            Toast.makeText(this, "New chat started", Toast.LENGTH_SHORT).show()
        }

        btnClearOutput.setOnClickListener {
            outputView.text = ""
        }

        findViewById<Button>(R.id.btn_voice_mic)?.setOnClickListener {
            if (voiceEngine.isListening) {
                voiceEngine.stopListening()
            } else {
                voiceEngine.startListening(
                    this,
                    onPartial = { partial ->
                        inputPrompt.setText(partial)
                    },
                    onResult = { recognized ->
                        inputPrompt.setText(recognized)
                        submitPrompt(recognized, fromVoice = true)
                    },
                    onError = { err ->
                        Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        btnSend.setOnClickListener {
            val text = inputPrompt.text.toString().trim()
            if (text.isNotEmpty() && !isBusy) {
                submitPrompt(text)
            }
        }

        inputPrompt.setOnEditorActionListener { _, _, _ ->
            val text = inputPrompt.text.toString().trim()
            if (text.isNotEmpty() && !isBusy) {
                submitPrompt(text)
                true
            } else {
                false
            }
        }

        btnAbort.setOnClickListener {
            abortPrompt()
        }
    }

    private fun setupChips() {
        findViewById<Button>(R.id.chip_torch)?.setOnClickListener { submitPrompt("Turn on the flashlight") }
        findViewById<Button>(R.id.chip_battery)?.setOnClickListener { submitPrompt("What's my battery percentage?") }
        findViewById<Button>(R.id.chip_youtube)?.setOnClickListener { submitPrompt("Open YouTube") }
        findViewById<Button>(R.id.chip_alarm)?.setOnClickListener { submitPrompt("Set an alarm for 7 AM") }
        findViewById<Button>(R.id.chip_timer)?.setOnClickListener { submitPrompt("Set a timer for 10 minutes") }
        findViewById<Button>(R.id.chip_volume_down)?.setOnClickListener { submitPrompt("Turn the volume down") }
        findViewById<Button>(R.id.chip_volume_up)?.setOnClickListener { submitPrompt("Turn the volume up") }
        findViewById<Button>(R.id.chip_weather)?.setOnClickListener { submitPrompt("What's the weather?") }
        findViewById<Button>(R.id.chip_home)?.setOnClickListener { submitPrompt("Take me home") }
        findViewById<Button>(R.id.chip_screenshot)?.setOnClickListener { submitPrompt("Take a screenshot") }
        findViewById<Button>(R.id.chip_settings)?.setOnClickListener { submitPrompt("Open Settings") }
        findViewById<Button>(R.id.chip_music)?.setOnClickListener { submitPrompt("Play some music") }
        findViewById<Button>(R.id.chip_help)?.setOnClickListener { submitExec("agy --help") }
        findViewById<Button>(R.id.chip_pwd)?.setOnClickListener { submitExec("pwd") }
        findViewById<Button>(R.id.chip_files)?.setOnClickListener { startActivity(Intent(this, BrowserActivity::class.java)) }
        findViewById<Button>(R.id.chip_opencode)?.setOnClickListener { startActivity(Intent(this, OpenCodeActivity::class.java)) }
        findViewById<Button>(R.id.chip_terminal)?.setOnClickListener { startActivity(Intent(this, TerminalActivity::class.java)) }
        findViewById<Button>(R.id.chip_diagnostics)?.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        findViewById<Button>(R.id.chip_capabilities)?.setOnClickListener { startActivity(Intent(this, com.pr4nav.jarvis.registry.CapabilitiesActivity::class.java)) }
        findViewById<Button>(R.id.chip_dashboard)?.setOnClickListener { submitPrompt("Show CPU usage") }
    }

    private fun updateServerState(online: Boolean, detail: String) {
        isServerOnline = online
        labelServerStatus.text = detail
        if (online) {
            dotStatus.setBackgroundColor(Color.parseColor("#10B981"))
            btnServerToggle.text = "STOP"
            btnServerToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))
        } else {
            dotStatus.setBackgroundColor(Color.parseColor("#EF4444"))
            btnServerToggle.text = "START"
            btnServerToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
        }
    }

    private fun submitPrompt(prompt: String, fromVoice: Boolean = false) {
        inputPrompt.setText("")
        appendUserPrompt(prompt)
        setBusy(true)

        val src = if (fromVoice) com.pr4nav.jarvis.core.InputSource.VOICE else com.pr4nav.jarvis.core.InputSource.TEXT
        com.pr4nav.jarvis.core.JarvisOrchestrator.processRequest(
            context = this,
            prompt = prompt,
            source = src,
            onChunk = { chunk ->
                runOnUiThread { appendToken(chunk) }
            },
            onComplete = { res ->
                runOnUiThread {
                    setBusy(false)
                    if (!res.isStreaming) {
                        appendSystem("${res.fullOutput}\n[Completed]")
                    } else {
                        appendSystem("\n[Completed]")
                    }
                    if (fromVoice && !res.isError) {
                        voiceEngine.speak(res.summary)
                    }
                }
            }
        )
    }

    private fun submitExec(cmd: String) {
        appendUserPrompt(cmd)
        setBusy(true)
        client.exec(cmd) { out, err, rc ->
            if (rc != -1) {
                setBusy(false)
                if (out.isNotEmpty()) appendToken(out)
                if (err.isNotEmpty()) appendError("\n" + err)
                appendSystem("\n[Exit code: $rc]")
            } else {
                Thread {
                    val res = Shell.ubuntu(cmd, timeoutMs = 60_000)
                    runOnUiThread {
                        setBusy(false)
                        if (res.out.isNotEmpty()) appendToken(res.out)
                        if (res.err.isNotEmpty()) appendError("\n" + res.err)
                        appendSystem("\n[Exit code: ${res.rc ?: 0}]")
                    }
                }.start()
            }
        }
    }

    private fun abortPrompt() {
        activeStreamHandle?.cancel()
        activeStreamHandle = null
        client.abort {
            setBusy(false)
            appendSystem("\n[Prompt Aborted]")
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        if (busy) {
            btnSend.visibility = View.GONE
            btnAbort.visibility = View.VISIBLE
            dotStatus.setBackgroundColor(Color.parseColor("#F59E0B"))
        } else {
            btnSend.visibility = View.VISIBLE
            btnAbort.visibility = View.GONE
            if (isServerOnline) {
                dotStatus.setBackgroundColor(Color.parseColor("#10B981"))
            }
        }
    }

    private fun appendUserPrompt(text: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val s = SpannableStringBuilder()
        s.append("\n[$ts] > ", ForegroundColorSpan(Color.parseColor("#38BDF8")), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.append(text + "\n", ForegroundColorSpan(Color.WHITE), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        outputView.append(s)
        scrollToBottom()
    }

    private fun appendToken(token: String) {
        outputView.append(token)
        scrollToBottom()
    }

    private fun appendStep(step: String) {
        val s = SpannableStringBuilder()
        s.append(step + " ", ForegroundColorSpan(Color.parseColor("#C084FC")), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        outputView.append(s)
        scrollToBottom()
    }

    private fun appendSystem(msg: String) {
        val s = SpannableStringBuilder()
        s.append(msg + "\n", ForegroundColorSpan(Color.parseColor("#94A3B8")), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        outputView.append(s)
        scrollToBottom()
    }

    private fun appendBanner(banner: String) {
        val s = SpannableStringBuilder()
        s.append(banner + "\n", ForegroundColorSpan(Color.parseColor("#38BDF8")), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        outputView.append(s)
        scrollToBottom()
    }

    private fun appendError(err: String) {
        val s = SpannableStringBuilder()
        s.append(err + "\n", ForegroundColorSpan(Color.parseColor("#EF4444")), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        outputView.append(s)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        outputScroller.post { outputScroller.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { voiceEngine.destroy() } catch (_: Exception) {}
    }
}
