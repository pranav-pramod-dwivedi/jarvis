package com.pr4nav.jarvis

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class TestSpec(
        val label: String,
        val displayCmd: String,
        val path: String,
        val args: Array<String>
    )

    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private lateinit var infoView: TextView
    private lateinit var scroller: ScrollView
    private lateinit var buttons: List<Button>

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<TestSpec>()
    private var currentSpec: TestSpec? = null
    private var currentId = 0
    private var reqCounter = 1000
    private var sentAtMs = 0L
    private var seqName = ""
    private var pendingAuto: String? = null

    companion object {
        private const val TAG = "JARVIS"
        private const val PERM = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_PKG = "com.termux"
        private const val TERMUX_SVC = "com.termux.app.RunCommandService"

        // Exact action/extras of the official Termux RUN_COMMAND interface
        // (TermuxConstants.RUN_COMMAND_SERVICE, termux-app >= 0.95;
        //  results require >= 0.109).
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val SH = "/data/data/com.termux/files/usr/bin/sh"
        private const val HOME = "/data/data/com.termux/files/home"

        private const val TIMEOUT_MS = 20_000L
        private const val REQ_PERM = 42

        private val SPEC_ECHO = TestSpec("echo", "echo JARVIS_TERMUX_OK", SH, arrayOf("-c", "echo JARVIS_TERMUX_OK"))
        private val SPEC_PWD = TestSpec("pwd", "pwd", SH, arrayOf("-c", "pwd"))
        private val SPEC_WHOAMI = TestSpec("whoami", "whoami", SH, arrayOf("-c", "whoami"))
        private val SPEC_UNAME = TestSpec("uname", "uname -a", SH, arrayOf("-c", "uname -a"))
        private val SPEC_RC = TestSpec("rc3", "exit 3  (rc must be 3)", SH, arrayOf("-c", "exit 3"))
        private val SPEC_STDERR = TestSpec("stderr", "echo stderr_line >&2; exit 7", SH, arrayOf("-c", "echo stderr_line >&2; exit 7"))

        private val SCRIPT_SETUP_CMD =
            "printf '%s\\n' '#!/data/data/com.termux/files/usr/bin/sh' 'echo \"Jarvis bridge works\"' 'date' 'pwd' " +
                "> \"\$HOME/jarvis_test.sh\"; chmod +x \"\$HOME/jarvis_test.sh\""
        private val SPEC_SCRIPT_SETUP = TestSpec("script-setup", "(create ~/jarvis_test.sh)", SH, arrayOf("-c", SCRIPT_SETUP_CMD))
        private val SPEC_SCRIPT_RUN = TestSpec("script-run", "~/jarvis_test.sh", "~/jarvis_test.sh", arrayOf())

        private fun basic4() = listOf(SPEC_ECHO, SPEC_PWD, SPEC_WHOAMI, SPEC_UNAME)
        private fun script2() = listOf(SPEC_SCRIPT_SETUP, SPEC_SCRIPT_RUN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status_view)
        outputView = findViewById(R.id.output_view)
        infoView = findViewById(R.id.info_view)
        scroller = findViewById(R.id.scroller)
        buttons = listOf(
            findViewById(R.id.btn_echo), findViewById(R.id.btn_pwd),
            findViewById(R.id.btn_whoami), findViewById(R.id.btn_uname),
            findViewById(R.id.btn_rc), findViewById(R.id.btn_stderr),
            findViewById(R.id.btn_test_termux), findViewById(R.id.btn_script)
        )

        findViewById<Button>(R.id.btn_echo).setOnClickListener { runSequence("single", listOf(SPEC_ECHO)) }
        findViewById<Button>(R.id.btn_pwd).setOnClickListener { runSequence("single", listOf(SPEC_PWD)) }
        findViewById<Button>(R.id.btn_whoami).setOnClickListener { runSequence("single", listOf(SPEC_WHOAMI)) }
        findViewById<Button>(R.id.btn_uname).setOnClickListener { runSequence("single", listOf(SPEC_UNAME)) }
        findViewById<Button>(R.id.btn_rc).setOnClickListener { runSequence("single", listOf(SPEC_RC)) }
        findViewById<Button>(R.id.btn_stderr).setOnClickListener { runSequence("single", listOf(SPEC_STDERR)) }
        findViewById<Button>(R.id.btn_test_termux).setOnClickListener { runSequence("TEST TERMUX", basic4()) }
        findViewById<Button>(R.id.btn_script).setOnClickListener { runSequence("TEST JARVIS SCRIPT", script2()) }

        if (!hasPerm()) requestPermissions(arrayOf(PERM), REQ_PERM)
        refreshInfo()

        pendingAuto = intent?.getStringExtra("auto")
        maybeRunAuto()
    }

    override fun onResume() {
        super.onResume()
        ResultBus.listener = ::onResult
    }

    override fun onPause() {
        ResultBus.listener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshInfo()
        if (requestCode == REQ_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                append("permission RUN_COMMAND granted")
                maybeRunAuto()
            } else {
                setStatus(FAILED, "Failed: RUN_COMMAND permission denied")
                append("DENIED. Grant manually: Settings -> Apps -> Jarvis -> Permissions -> Additional permissions")
            }
        }
    }

    private fun hasPerm(): Boolean =
        ContextCompat.checkSelfPermission(this, PERM) == PackageManager.PERMISSION_GRANTED

    private fun refreshInfo() {
        val ver = try {
            packageManager.getPackageInfo(TERMUX_PKG, 0).versionName
        } catch (e: Exception) {
            null
        }
        infoView.text =
            "Termux: ${ver ?: "NOT INSTALLED"}  |  RUN_COMMAND perm: ${if (hasPerm()) "granted" else "NOT granted"}\n" +
                "Offline by design: no INTERNET permission, pure local intents"
        if (ver == null) setStatus(FAILED, "Failed: Termux not installed")
    }

    private fun maybeRunAuto() {
        val auto = pendingAuto ?: return
        if (!hasPerm()) return
        pendingAuto = null
        when (auto) {
            "basic" -> runSequence("AUTO-BASIC", basic4())
            "script" -> runSequence("AUTO-SCRIPT", script2())
            "all" -> runSequence("AUTO-ALL", basic4() + script2())
        }
    }

    private fun runSequence(name: String, specs: List<TestSpec>) {
        if (currentSpec != null || !hasPerm()) return
        queue.clear()
        queue.addAll(specs)
        seqName = name
        Log.i(TAG, "SEQ_BEGIN $name (${specs.size} steps)")
        stepNext()
    }

    private fun stepNext() {
        val next = queue.pollFirst() ?: run {
            Log.i(TAG, "SEQ_END $seqName")
            if (seqName.startsWith("AUTO")) Log.i(TAG, "AUTO_DONE $seqName")
            setButtonsEnabled(true)
            return
        }
        send(next)
    }

    private fun send(spec: TestSpec) {
        currentSpec = spec
        sentAtMs = System.currentTimeMillis()
        val id = ++reqCounter
        currentId = id

        val piFlags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val resultIntent = Intent(this, TermuxResultReceiver::class.java)
            .putExtra(ResultBus.EXTRA_LABEL, spec.label)
            .putExtra(ResultBus.EXTRA_CMD, spec.displayCmd)
        val pi = PendingIntent.getBroadcast(applicationContext, id, resultIntent, piFlags)

        val intent = Intent()
            .setClassName(TERMUX_PKG, TERMUX_SVC)
            .setAction(ACTION_RUN_COMMAND)
            .putExtra(EXTRA_PATH, spec.path)
            .putExtra(EXTRA_ARGUMENTS, spec.args)
            .putExtra(EXTRA_WORKDIR, HOME)
            .putExtra(EXTRA_BACKGROUND, true)
            .putExtra(EXTRA_COMMAND_LABEL, "$seqName/${spec.label}")
            .putExtra(EXTRA_PENDING_INTENT, pi)

        setButtonsEnabled(false)
        setStatus(WAITING, "Waiting… sent '$${spec.label}' to Termux")
        append("$ ${spec.displayCmd}")
        handler.postDelayed({ onTimeout(id) }, TIMEOUT_MS)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) {
            handler.removeCallbacksAndMessages(null)
            completeStep(TermuxResult(spec.label, spec.displayCmd, null, null, null, null, null,
                internalError = "startService failed: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private fun onTimeout(id: Int) {
        val spec = currentSpec ?: return
        if (id != currentId) return
        completeStep(TermuxResult(spec.label, spec.displayCmd, null, null, null, null, null, internalError =
            "TIMEOUT after ${TIMEOUT_MS / 1000}s, no result from Termux. Checks: Termux installed & opened once? " +
                "allow-external-apps=true in ~/.termux/termux.properties? RUN_COMMAND permission granted?"))
    }

    private fun onResult(r: TermuxResult) {
        if (r.label != currentSpec?.label) {
            append("(ignored unmatched result for '${r.label}')")
            return
        }
        handler.removeCallbacksAndMessages(null)
        completeStep(r)
    }

    private fun completeStep(r: TermuxResult) {
        val spec = currentSpec
        currentSpec = null
        val ms = System.currentTimeMillis() - sentAtMs

        append("stdout: ${r.stdout?.takeIf { it.isNotBlank() } ?: "<empty>"}")
        append("stderr: ${r.stderr?.takeIf { it.isNotBlank() } ?: "<empty>"}")

        val internalProblem = r.internalError != null || (r.err != null && r.err != -1)
        if (internalProblem) {
            setStatus(FAILED, "Failed: ${r.internalError ?: r.errmsg}")
        } else {
            val rcPart = "rc=${r.exitCode}"
            if (r.exitCode != null && r.exitCode != 0) append("NOTE: command ran, nonzero $rcPart")
            setStatus(CONNECTED, "Connected ($rcPart)")
        }
        append("rc=${r.exitCode ?: "?"} err=${r.err ?: "?"} errmsg=${r.errmsg ?: ""} ${ms}ms ${if (internalProblem) "[FAILED]" else "[OK]"}")
        Log.i(TAG, "STEP_DONE $seqName/${r.label} rc=${r.exitCode} err=${r.err} internal=${r.internalError} ${ms}ms")
        append("")
        stepNext()
    }

    // ---- UI helpers ----

    private val IDLE = "IDLE"; private val WAITING = "WAITING"
    private val CONNECTED = "CONNECTED"; private val FAILED = "FAILED"

    private fun setStatus(state: String, detail: String) {
        statusView.text = detail
        statusView.setBackgroundColor(
            when (state) {
                CONNECTED -> 0xFF2E7D32.toInt()
                FAILED -> 0xFFC62828.toInt()
                WAITING -> 0xFFF9A825.toInt()
                else -> 0xFF37474F.toInt()
            }
        )
    }

    private fun setButtonsEnabled(enabled: Boolean) = buttons.forEach { it.isEnabled = enabled }

    private fun append(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        outputView.append("[$ts] $line\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }
}
