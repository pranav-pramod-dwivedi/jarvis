package com.pr4nav.jarvis

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal terminal diagnostic. One TextView, one ScrollView, one button per
 * smoke-test command. Uses the already-proven Shell.termux() path.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private val tests = listOf("pwd", "echo JARVIS_TERMUX_OK", "id", "uname -a")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: building minimal layout")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        status = TextView(this).apply {
            text = "JARVIS Terminal minimal test"
            setTextColor(Color.parseColor("#4FD1C5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        root.addView(status)

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#101010"))
        }
        out = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = ""
        }
        scroll.addView(out)
        root.addView(scroll)

        val tests = listOf("pwd", "id", "uname -a", "agy --version", "python3 --version", "opencode --version")
        val hscroll = android.widget.HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (cmd in tests) {
            val b = Button(this).apply {
                text = cmd
                textSize = 11f
                setOnClickListener { runCmd(cmd) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(38)
                ).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
            }
            btnRow.addView(b)
        }
        hscroll.addView(btnRow)
        root.addView(hscroll)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) }
        }
        val cmdInput = android.widget.EditText(this).apply {
            hint = "Execute in proot distro ubuntu..."
            setHintTextColor(Color.parseColor("#718096"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(Typeface.MONOSPACE)
            setBackgroundColor(Color.parseColor("#1A202C"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val runBtn = Button(this).apply {
            text = "RUN"
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                setMargins(dp(6), 0, 0, 0)
            }
            setOnClickListener {
                val t = cmdInput.text.toString().trim()
                if (t.isNotEmpty()) {
                    cmdInput.setText("")
                    runCmd(t)
                }
            }
        }
        cmdInput.setOnEditorActionListener { _, _, _ ->
            val t = cmdInput.text.toString().trim()
            if (t.isNotEmpty()) {
                cmdInput.setText("")
                runCmd(t)
            }
            true
        }
        inputRow.addView(cmdInput)
        inputRow.addView(runBtn)
        root.addView(inputRow)

        setContentView(root)
        Log.i(TAG, "onCreate: layout attached, running pwd automatically")
        appendLine("=== JARVIS Terminal (proot-distro ubuntu) ===")
        appendLine("Backend: Shell.ubuntu() -> TermuxBridge.execute(proot-distro)")
        appendLine("Tap a button or type any command to run in Ubuntu.")
        appendLine("")
        runCmd("pwd")
    }

    private fun runCmd(cmd: String) {
        appendLine("---")
        appendLine("> $cmd")
        val guardErr = CmdGuard.check(cmd)
        if (guardErr != null) {
            appendLine("⚠️ Command Blocked by Safety Guard: $guardErr")
            status.text = "blocked by guard"
            return
        }
        status.text = "running: $cmd"
        Thread {
            val r = Shell.termux(cmd, 30_000)
            Log.i(TAG, "TERM result cmd=$cmd rc=${r.rc} ms=${r.ms} via=${r.via}")
            Log.i(TAG, "TERM stdout=${r.out.take(2000)}")
            Log.i(TAG, "TERM stderr=${r.err.take(2000)}")
            runOnUiThread {
                appendLine("exit: ${r.rc ?: "?"}  (${r.ms}ms via=${r.via})")
                if (r.out.isNotBlank()) appendLine("stdout:\n${r.out.trimEnd()}")
                if (r.err.isNotBlank()) appendLine("stderr:\n${r.err.trimEnd()}")
                status.text = "last: $cmd -> rc=${r.rc ?: "?"}"
            }
        }.start()
    }

    private fun appendLine(s: String) {
        out.append(s)
        out.append("\n")
        scroll.post { scroll.fullScroll(ViewGroup.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object { private const val TAG = "JARVIS" }
}
