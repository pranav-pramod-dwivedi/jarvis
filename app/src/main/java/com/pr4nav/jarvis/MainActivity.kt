package com.pr4nav.jarvis

import android.content.Intent
import android.content.pm.PackageManager
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.capabilities.RootCapability
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private lateinit var stageView: TextView
    private lateinit var infoView: TextView
    private lateinit var storageView: TextView
    private lateinit var scroller: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private val stages = LinkedHashMap<String, String>()
    private var polling = false
    private var pendingAuto: String? = null

    companion object {
        private const val TAG = "JARVIS"
        private const val POLL_LABEL = "__poll"
        private const val POLL_MS = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TermuxBridge.init(this)
        Fs.init(this)
        Capabilities.init(this)
        Thread { RootCapability.detect() }.start()

        statusView = findViewById(R.id.status_view)
        outputView = findViewById(R.id.output_view)
        stageView = findViewById(R.id.stage_view)
        infoView = findViewById(R.id.info_view)
        storageView = findViewById(R.id.storage_view)
        scroller = findViewById(R.id.scroller)

        findViewById<Button>(R.id.btn_open_files).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<Button>(R.id.btn_nav_agent).setOnClickListener {
            startActivity(Intent(this, AgentActivity::class.java))
        }
        try {
            findViewById<Button>(R.id.btn_open_opencode)?.setOnClickListener {
                startActivity(Intent(this, OpenCodeActivity::class.java))
            }
        } catch (_: Exception) {}
        findViewById<Button>(R.id.btn_nav_commander).setOnClickListener {
            startActivity(Intent(this, CommanderActivity::class.java))
        }
        findViewById<Button>(R.id.btn_nav_terminal).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        findViewById<Button>(R.id.btn_nav_status).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_permissions).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_bootstrap).setOnClickListener { launchBootstrap() }
        findViewById<Button>(R.id.btn_auth).setOnClickListener { openAuthSession() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopEverything() }

        refreshInfo()

        val prefs = getPreferences(MODE_PRIVATE)
        if (!prefs.getBoolean("perm_asked_v1", false)) {
            prefs.edit().putBoolean("perm_asked_v1", true).apply()
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        pendingAuto = intent?.getStringExtra("auto")
        maybeRunAuto()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAuto = intent.getStringExtra("auto")
        maybeRunAuto()
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
        ResultBus.listeners += ::onResult
    }

    override fun onPause() {
        ResultBus.listeners -= ::onResult
        super.onPause()
    }

    private fun refreshInfo() {
        val ver = try {
            packageManager.getPackageInfo(TermuxBridge.TERMUX_PKG, 0).versionName
        } catch (e: Exception) { null }
        val perm = TermuxBridge.hasPermission()
        infoView.text = "Termux ${ver ?: "NOT INSTALLED"} · bridge ${if (perm) "permitted" else "not permitted"} · ${Fs.accessLevel}"
        if (ver == null) setStatus(FAILED, "Failed: Termux not installed")

        try {
            val (avail, total) = Fs.Java.storageInfo()
            val gb = { v: Long -> String.format(Locale.US, "%.1f GB", v / 1e9) }
            storageView.text = "Internal Storage\n${gb(avail)} available of ${gb(total)}\nPath: /storage/emulated/0"
        } catch (e: Exception) {
            storageView.text = "Internal Storage: ${e.message}"
        }

        if (!perm) requestPermissions(arrayOf(TermuxBridge.PERM), 42)
        if (!Fs.hasAllFiles && Build.VERSION.SDK_INT >= 30) {
            append("Tip: grant All-Files-Access for full file-manager power (Status screen → Grant).")
        }
    }

    private fun maybeRunAuto() {
        val auto = pendingAuto ?: return
        if (!TermuxBridge.hasPermission()) return
        if (polling) { Log.i(TAG, "auto '$auto' ignored: busy"); pendingAuto = null; return }
        pendingAuto = null
        when (auto) {
            "bootstrap" -> launchBootstrap()
            "auth" -> openAuthSession()
            "term" -> startActivity(Intent(this, TerminalActivity::class.java).putExtra("autotest", true))
            "selftest" -> SelfTest.run(this) { line -> runOnUiThread { append(line) } }
            "shell" -> {
                val cmd = intent.getStringExtra("shell_cmd") ?: intent.getStringExtra("cmd") ?: ""
                Thread {
                    val r = Shell.termux(cmd, 60_000)
                    Log.i(TAG, "SHELL_DIAG cmd=$cmd")
                    Log.i(TAG, "SHELL_DIAG stdout=${r.out.take(8000)}")
                    Log.i(TAG, "SHELL_DIAG stderr=${r.err.take(2000)} rc=${r.rc} via=${r.via} ms=${r.ms}")
                }.start()
            }
        }
    }

    // ================= INSTALLER =================

    private fun termuxScriptCmd(): TermuxBridgeSpec {
        val script = assets.open("bootstrap.sh").bufferedReader().readText()
        val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
        val chunks = mutableListOf<String>()
        for (i in 0 until b64.length step 2000) chunks.add(b64.substring(i, minOf(i + 2000, b64.length)))
        val sb = StringBuilder("mkdir -p ~/jarvis/bin ~/jarvis/logs ~/jarvis/state && ")
        sb.append("echo '${chunks[0]}' > ~/jarvis/bin/bootstrap.sh.b64")
        for (i in 1 until chunks.size) sb.append(" && echo '${chunks[i]}' >> ~/jarvis/bin/bootstrap.sh.b64")
        sb.append(" && base64 -d ~/jarvis/bin/bootstrap.sh.b64 > ~/jarvis/bin/bootstrap.sh")
        sb.append(" && chmod +x ~/jarvis/bin/bootstrap.sh")
        sb.append(" && (nohup ~/jarvis/bin/bootstrap.sh >>~/jarvis/logs/boot.out 2>&1 &)")
        sb.append(" && echo BOOTSTRAP_LAUNCHED")
        return TermuxBridgeSpec("__launch", sb.toString())
    }

    private fun launchBootstrap() {
        if (!TermuxBridge.hasPermission()) { setStatus(FAILED, "Grant RUN_COMMAND permission first"); return }
        append("Deploying bootstrap script…")
        stages.clear(); renderStages()
        Thread {
            TermuxBridge.execute("__clear", "> ~/jarvis/state/events.ndjson 2>/dev/null", 10_000)
            val r = TermuxBridge.execute("__launch", termuxScriptCmd().cmd, 90_000)
            runOnUiThread {
                if (r?.stdout?.contains("BOOTSTRAP_LAUNCHED") == true) {
                    append("Bootstrap running inside Termux. Tracking stages…")
                    startPolling()
                } else {
                    setStatus(FAILED, "Launch failed: ${r?.internalError ?: r?.stderr ?: "no response"}")
                }
            }
        }.start()
    }

    private fun openAuthSession() {
        if (!TermuxBridge.hasPermission()) return
        Thread {
            TermuxBridge.execute("__auth", "~/jarvis/bin/bootstrap.sh auth", 15_000, background = false)
            runOnUiThread {
                setStatus(WAITING, "Finish login in the Termux window…")
                append("Interactive auth opened. After login, tap INSTALL / BOOTSTRAP again.")
            }
        }.start()
    }

    private fun stopEverything() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        Thread { TermuxBridge.execute("__stop", "pkill -f jarvis/bin/bootstrap.sh; echo STOPPED", 10_000) }.start()
        setStatus(IDLE, "Stopped")
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollTick()
    }

    private fun pollTick() {
        if (!polling) return
        Thread {
            val r = TermuxBridge.execute(POLL_LABEL, "tail -n 80 ~/jarvis/state/events.ndjson 2>/dev/null || echo NO_STATE", 20_000)
            runOnUiThread { if (r != null) handlePoll(r.stdout) }
            handler.postDelayed({ pollTick() }, POLL_MS)
        }.start()
    }

    private fun handlePoll(stdout: String?) {
        if (stdout.isNullOrBlank() || stdout.contains("NO_STATE")) return
        for (line in stdout.lines()) {
            val t = line.trim()
            if (!t.startsWith("{")) continue
            try {
                val o = JSONObject(t)
                val st = o.optString("stage") ?: continue
                val status = o.optString("status")
                val msg = o.optString("msg")
                stages[st] = "$status|$msg"
                Log.i(TAG, "STAGE $st $status $msg")
                if (status == "failed" || (st == "jarvis" && status == "done")) polling = false
            } catch (_: Exception) {}
        }
        renderStages(); renderPill()
    }

    private fun renderStages() {
        if (stages.isEmpty()) { stageView.text = ""; return }
        val sb = StringBuilder()
        for ((name, v) in stages) {
            val p = v.split("|", limit = 2)
            val sym = when (p.getOrNull(0)) {
                "done" -> "✓"; "running" -> "⟳"; "fixing" -> "🔧"; "failed" -> "✗"; "warn" -> "△"; "auth_required" -> "⚠"; else -> "○"
            }
            sb.append(sym).append(' ').append(name.padEnd(9)).append("  ").append(p.getOrNull(1) ?: "").append('\n')
        }
        stageView.text = sb.toString()
    }

    private fun renderPill() {
        var failed: String? = null; var authReq = false; var done = false; var running = ""
        for ((name, v) in stages) {
            val p = v.split("|", limit = 2)
            when (p.getOrNull(0)) {
                "failed" -> if (failed == null) failed = p.getOrNull(1) ?: name
                "auth_required" -> authReq = true
                "running", "fixing" -> running = p.getOrNull(1) ?: ""
            }
            if (name == "jarvis" && p.getOrNull(0) == "done") done = true
        }
        when {
            done -> setStatus(CONNECTED, "Jarvis ready ✓")
            failed != null -> setStatus(FAILED, "Failed: $failed")
            authReq -> setStatus(WAITING, "Authentication required — tap OPEN AUTH")
            else -> setStatus(WAITING, running.ifBlank { "Preparing Jarvis…" })
        }
    }

    // ================= result routing =================

    private fun onResult(r: TermuxResult) {
        if (r.label == POLL_LABEL) handlePoll(r.stdout)
    }

    // ---- helpers ----
    data class TermuxBridgeSpec(val label: String, val cmd: String)

    private val IDLE = "IDLE"; private val WAITING = "WAITING"
    private val CONNECTED = "CONNECTED"; private val FAILED = "FAILED"

    private fun setStatus(state: String, detail: String) {
        statusView.text = detail
        statusView.setBackgroundColor(
            when (state) {
                CONNECTED -> 0xFF2E7D32.toInt(); FAILED -> 0xFFC62828.toInt()
                WAITING -> 0xFFF9A825.toInt(); else -> 0xFF37474F.toInt()
            }
        )
    }

    private fun append(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        outputView.append("[$ts] $line\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }
}
