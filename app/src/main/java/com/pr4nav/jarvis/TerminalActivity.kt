package com.pr4nav.jarvis

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * In-app terminal backed by Termux (RUN_COMMAND bridge).
 * Persistent cwd, command history, TAB completion, extra keys (ESC/CTRL/FN/arrows/
 * HOME/END/PGUP/PGDN). VERIFY/OPEN only — destructive commands are blocked by CmdGuard.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var scroller: ScrollView
    private lateinit var input: EditText
    private lateinit var prompt: TextView
    private lateinit var ctrlBtn: Button

    private val history = ArrayList<String>()
    private var histIdx = 0
    private var cwd = "/data/data/com.termux/files/home"
    private var ctrlArmed = false
    private var running = false

    private val home = "/data/data/com.termux/files/home"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        out = findViewById(R.id.term_out)
        scroller = findViewById(R.id.term_scroller)
        input = findViewById(R.id.term_input)
        prompt = findViewById(R.id.term_prompt)
        ctrlBtn = findViewById(R.id.k_ctrl)

        findViewById<Button>(R.id.term_send).setOnClickListener { submit() }
        findViewById<Button>(R.id.k_esc).setOnClickListener { input.setText("") }
        findViewById<Button>(R.id.k_tab).setOnClickListener { complete() }
        findViewById<Button>(R.id.k_ctrl).setOnClickListener {
            ctrlArmed = !ctrlArmed
            ctrlBtn.setTextColor(if (ctrlArmed) 0xFF4FD1C5.toInt() else 0xFF9FD8FF.toInt())
            toast(if (ctrlArmed) "CTRL armed: next key = C-a C-e C-u C-w C-c C-k C-l" else "CTRL off")
        }
        findViewById<Button>(R.id.k_fn).setOnClickListener {
            val r = findViewById<View>(R.id.term_fn_row)
            r.visibility = if (r.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.k_up).setOnClickListener { hist(-1) }
        findViewById<Button>(R.id.k_down).setOnClickListener { hist(1) }
        findViewById<Button>(R.id.k_left).setOnClickListener { moveCursor(-1) }
        findViewById<Button>(R.id.k_right).setOnClickListener { moveCursor(1) }
        findViewById<Button>(R.id.k_home).setOnClickListener { input.setSelection(0) }
        findViewById<Button>(R.id.k_end).setOnClickListener { input.setSelection(input.text.length) }
        findViewById<Button>(R.id.k_pgup).setOnClickListener { scroller.pageScroll(View.FOCUS_UP) }
        findViewById<Button>(R.id.k_pgdn).setOnClickListener { scroller.pageScroll(View.FOCUS_DOWN) }
        findViewById<Button>(R.id.k_stop).setOnClickListener { stopRequested() }

        findViewById<Button>(R.id.fk_clear).setOnClickListener { out.text = "" }
        findViewById<Button>(R.id.fk_hist).setOnClickListener { history.forEachIndexed { i, h -> append("${i + 1}: $h") } }
        findViewById<Button>(R.id.fk_pwd).setOnClickListener { submitText("pwd") }
        findViewById<Button>(R.id.fk_ls).setOnClickListener { submitText("ls -la") }
        findViewById<Button>(R.id.fk_date).setOnClickListener { submitText("date") }
        findViewById<Button>(R.id.fk_uname).setOnClickListener { submitText("uname -a") }
        findViewById<Button>(R.id.fk_df).setOnClickListener { submitText("df -h \$HOME") }

        input.setOnKeyListener { _, kc, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (kc) {
                KeyEvent.KEYCODE_DPAD_UP -> { hist(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { hist(1); true }
                KeyEvent.KEYCODE_TAB -> { complete(); true }
                KeyEvent.KEYCODE_MOVE_HOME -> { input.setSelection(0); true }
                KeyEvent.KEYCODE_MOVE_END -> { input.setSelection(input.text.length); true }
                KeyEvent.KEYCODE_U -> if (ctrlArmed) { input.setText(""); disarm(); true } else false
                KeyEvent.KEYCODE_A -> if (ctrlArmed) { input.setSelection(0); disarm(); true } else false
                KeyEvent.KEYCODE_E -> if (ctrlArmed) { input.setSelection(input.text.length); disarm(); true } else false
                KeyEvent.KEYCODE_W -> if (ctrlArmed) { delWord(); disarm(); true } else false
                KeyEvent.KEYCODE_C -> if (ctrlArmed) { input.setText(""); disarm(); toast("^C — line cleared"); true } else false
                KeyEvent.KEYCODE_L -> if (ctrlArmed) { out.text = ""; disarm(); true } else false
                KeyEvent.KEYCODE_K -> if (ctrlArmed) { input.setText(input.text.substring(0, input.selectionStart)); disarm(); true } else false
                else -> false
            }
        }

        paintPrompt()

        if (intent.getBooleanExtra("autotest", false)) {
            append("self-check: running a random verification command through Termux…")
            submitText("echo TERMINAL_OK_${(100..999).random()} && uname -sr && pwd")
        }
    }

    private fun disarm() { ctrlArmed = false; ctrlBtn.setTextColor(0xFF9FD8FF.toInt()) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun append(s: String) {
        out.append("$s\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun paintPrompt() {
        prompt.text = "jarvis:${cwd.replace(home, "~")} · ${if (running) "running…" else "ready"}"
    }

    private fun hist(delta: Int) {
        if (history.isEmpty()) return
        histIdx = (histIdx + delta).coerceIn(0, history.size)
        input.setText(if (histIdx == history.size) "" else history[histIdx])
        input.setSelection(input.text.length)
    }

    private fun moveCursor(delta: Int) {
        val p = (input.selectionStart + delta).coerceIn(0, input.text.length)
        input.setSelection(p)
    }

    private fun delWord() {
        val t = input.text
        var p = input.selectionStart
        while (p > 0 && t[p - 1] == ' ') p--
        while (p > 0 && t[p - 1] != ' ') p--
        input.setText(t.substring(0, p) + t.substring(input.selectionEnd))
        input.setSelection(p)
    }

    private fun stopRequested() {
        toast("No PTY: running commands can't be interrupted remotely. They finish or hit timeout.")
        thread { TermuxBridge.execute("__termnote", "pkill -f 'jarvis_term' 2>/dev/null; true", 5_000) }
    }

    private fun complete() {
        val t = input.text.toString()
        val token = t.substringAfterLast(' ', "")
        thread {
            val r = Shell.termux("ls -1A ${q(cwd)} 2>/dev/null", 10_000)
            val opts = r.out.lines().filter { it.startsWith(token) && token.isNotEmpty() }
            runOnUiThread {
                when {
                    token.isEmpty() -> append("(cwd) " + r.out.lines().take(40).joinToString("  "))
                    opts.size == 1 -> {
                        input.setText(t.dropLast(token.length) + opts[0] + if (r.out.contains(opts[0] + "/")) "/" else "")
                        input.setSelection(input.text.length)
                    }
                    opts.isEmpty() -> toast("no match")
                    else -> {
                        val common = commonPrefix(opts)
                        input.setText(t.dropLast(token.length) + common)
                        input.setSelection(input.text.length)
                        append("options: " + opts.take(30).joinToString("  "))
                    }
                }
            }
        }
    }

    private fun commonPrefix(list: List<String>): String {
        var p = list[0]
        for (s in list) { while (!s.startsWith(p)) p = p.dropLast(1) }
        return p
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private val commandQueue = ArrayDeque<String>()

    private fun submit() { val t = input.text.toString(); input.setText(""); submitText(t) }

    private fun submitText(cmdRaw: String) {
        val cmd = cmdRaw.trim()
        if (cmd.isEmpty()) return
        // immediate UI commands not queued
        if (cmd == "clear") { out.text = ""; return }
        if (cmd == "exit") { finish(); return }

        CmdGuard.check(cmd)?.let { append("✗ $it"); return }

        history.add(cmd); histIdx = history.size
        commandQueue.addLast(cmd)
        append("queued: $cmd  [queue=${commandQueue.size}]")
        if (!running) processNext()
    }

    private fun processNext() {
        if (commandQueue.isEmpty()) { running = false; paintPrompt(); return }
        val cmd = commandQueue.removeFirst()
        // cd is handled as a queued command too, so cwd order is preserved
        val cdMatch = Regex("^cd(?:\\s+(.*))?$").find(cmd)
        if (cdMatch != null) {
            val arg = cdMatch.groupValues?.get(1)?.trim() ?: ""
            val target = when {
                arg.isEmpty() || arg == "~" -> home
                arg.startsWith("/") -> arg
                else -> cwd.trimEnd('/') + "/" + arg
            }.trimEnd('/')
            running = true; paintPrompt()
            append("jarvis:${cwd.replace(home, "~")}$ $cmd")
            thread {
                val r = Shell.termux("test -d ${q(target)} && echo CD_OK", 15_000)
                runOnUiThread {
                    if (r.out.contains("CD_OK")) { cwd = target; append("→ $target") }
                    else append("cd: no such directory: $target")
                    running = false; paintPrompt()
                    processNext()
                }
            }
            return
        }

        running = true; paintPrompt()
        append("jarvis:${cwd.replace(home, "~")}$ $cmd")
        thread {
            val wrapped = "cd ${q(cwd)} 2>/dev/null\n$cmd"
            val r = Shell.termux(wrapped, 120_000)
            runOnUiThread {
                append("rc=${r.rc ?: "?"} ${r.ms}ms${if (r.timedOut) " [TIMEOUT]" else ""} via=${r.via}")
                if (r.out.isNotBlank()) append(r.out.take(8000))
                if (r.err.isNotBlank()) append("stderr: ${r.err.take(1500)}")
                if (commandQueue.isNotEmpty()) append("(${commandQueue.size} queued)")
                append("──")
                running = false; paintPrompt()
                processNext()
            }
        }
    }

    override fun onBackPressed() {
        if (running) { toast("command still running — press STOP or wait"); return }
        super.onBackPressed()
    }
}
