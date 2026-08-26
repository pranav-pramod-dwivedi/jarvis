package com.pr4nav.jarvis

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Commander: genuine console over the SAME Shell abstraction the agent uses.
 * Shows command, via (termux/local/root), stdout, stderr, exit code, duration, timeout.
 */
class CommanderActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var scroller: ScrollView
    private lateinit var input: EditText
    private var via: String = "termux"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_commander)
        log = findViewById(R.id.cmd_log)
        scroller = findViewById(R.id.cmd_scroller)
        input = findViewById(R.id.cmd_input)
        updateCtx()

        val bt = findViewById<Button>(R.id.cmd_via_termux)
        val bl = findViewById<Button>(R.id.cmd_via_local)
        val br = findViewById<Button>(R.id.cmd_via_root)
        fun paint() {
            bt.setBackgroundColor(if (via == "termux") 0xFF16232E.toInt() else 0x00000000)
            bl.setBackgroundColor(if (via == "local") 0xFF16232E.toInt() else 0x00000000)
            br.setBackgroundColor(if (via == "root") 0xFF16232E.toInt() else 0x00000000)
        }
        bt.setOnClickListener { via = "termux"; paint(); updateCtx() }
        bl.setOnClickListener { via = "local"; paint(); updateCtx() }
        br.setOnClickListener { via = "root"; paint(); updateCtx() }
        paint()

        findViewById<Button>(R.id.cmd_run).setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
    }

    private fun updateCtx() {
        findViewById<TextView>(R.id.cmd_ctx).text =
            "runner: $via · cwd: ${SessionState.dir} · fs: ${Fs.accessLevel}"
    }

    private fun append(s: String) {
        log.append("$s\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submit(): Boolean {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return true
        input.setText("")
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        append("[$ts] $via\$ $cmd")
        CmdGuard.check(cmd)?.let { append("✗ $it"); append("──"); return true }
        findViewById<Button>(R.id.cmd_run).isEnabled = false
        thread {
            val r = when (via) {
                "local" -> Shell.local(cmd, 30_000)
                "root" -> Shell.root(cmd, 30_000)
                else -> Shell.termux(cmd, 60_000)
            }
            runOnUiThread {
                findViewById<Button>(R.id.cmd_run).isEnabled = true
                append("via=${r.via} rc=${r.rc ?: "none"} ${r.ms}ms${if (r.timedOut) " [TIMEOUT]" else ""}")
                if (r.out.isNotBlank()) append(r.out.take(6000))
                if (r.err.isNotBlank()) append("stderr: ${r.err.take(1500)}")
                if (r.out.isBlank() && r.err.isBlank()) append("(no output)")
                append("──")
                updateCtx()
            }
        }
        return true
    }
}
