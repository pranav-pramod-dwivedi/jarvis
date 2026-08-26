package com.pr4nav.jarvis

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class AgentActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var scroller: ScrollView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent)
        log = findViewById(R.id.agent_log)
        scroller = findViewById(R.id.agent_scroller)
        input = findViewById(R.id.agent_input)
        findViewById<Button>(R.id.agent_send).setOnClickListener { submit() }
        updateCtx()
    }

    override fun onResume() { super.onResume(); updateCtx() }

    private fun updateCtx() {
        findViewById<TextView>(R.id.agent_ctx).text =
            "cwd: ${SessionState.dir}\nstorage: ${Fs.accessLevel} · termux: ${if (Shell.termuxReachable()) "up" else "down"}"
    }

    private fun out(s: String) = runOnUiThread {
        log.append("$s\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        updateCtx()
    }

    private fun submit() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        out("› $q")
        thread {
            try { handle(q) } catch (e: Exception) { out("✗ ${e.message}") }
        }
    }

    private fun handle(q: String) {
        val lower = q.lowercase()
        val arg = q.split(" ", limit = 2).getOrNull(1)?.trim() ?: ""

        when {
            lower == "help" -> out(
                "Commands:\n" +
                    "  ls [path]          list directory\n" +
                    "  open <path>        set current folder\n" +
                    "  read <path>        read a text file\n" +
                    "  write <path> <text> create/overwrite file\n" +
                    "  mkdir <path>       create folder\n" +
                    "  delete <path>      delete file/folder\n" +
                    "  find <name>        search files from current folder\n" +
                    "  search <text>      search file contents (grep) via Termux\n" +
                    "  stat <path>        file info\n" +
                    "  run <cmd>          real shell command in Termux\n" +
                    "  opencode <prompt>  ask OpenCode (needs bootstrap+auth)\n" +
                    "  projects           discover dev projects"
            )

            lower.startsWith("ls") -> {
                val p = arg.ifBlank { SessionState.dir }
                val list = Fs.list(p)
                out("backend=${Fs.backendFor(p).id} · ${list.size} items")
                list.take(50).forEach { e ->
                    out((if (e.isDir) "📁 " else "📄 ") + e.name + (if (e.isDir) "/" else "  (${e.size}B)"))
                }
            }

            lower.startsWith("open ") -> {
                Fs.stat(arg).let { if (!it.isDir) out("⚠ not a folder, but noted") }
                SessionState.dir = arg
                out("cwd → $arg")
            }

            lower.startsWith("read ") -> {
                val content = Fs.read(arg)
                out("backend=${Fs.backendFor(arg).id} · ${content.length} chars")
                out(content.take(2000) + if (content.length > 2000) "\n…(truncated)" else "")
            }

            lower.startsWith("write ") -> {
                val path = q.split(" ", limit = 3).getOrNull(1) ?: throw Fs.FsException("usage: write <path> <text>")
                val text = q.split(" ", limit = 3).getOrNull(2) ?: ""
                Fs.write(path, text)
                out("✓ wrote ${text.length} chars → $path (backend=${Fs.backendFor(path).id})")
            }

            lower.startsWith("mkdir ") -> { Fs.mkdir(arg); out("✓ mkdir $arg") }
            lower.startsWith("delete ") -> { Fs.delete(arg); out("✓ deleted $arg") }

            lower.startsWith("find ") -> {
                val res = Fs.search(SessionState.dir, arg, 50)
                out("${res.size} matches for '$arg' under ${SessionState.dir}")
                res.take(30).forEach { out((if (it.isDir) "📁 " else "📄 ") + it.path) }
            }

            lower.startsWith("search ") -> {
                val r = Shell.termux("grep -rn --include='*' -m 3 ${shellQ(arg)} ${shellQ(SessionState.dir)} 2>/dev/null | head -30")
                if (r.out.isBlank()) out("no matches (or path not readable from Termux)")
                else out(r.out)
            }

            lower.startsWith("stat ") -> {
                val e = Fs.stat(arg)
                out("name=${e.name}\ndir=${e.isDir}\nsize=${e.size}\nmodified=${e.modified}\nbackend=${Fs.backendFor(arg).id}")
            }

            lower.startsWith("run ") -> {
                val r = Shell.termux(arg, 60_000)
                out("rc=${r.rc} ${r.ms}ms via=${r.via}")
                if (r.out.isNotBlank()) out(r.out.take(3000))
                if (r.err.isNotBlank()) out("stderr: ${r.err.take(800)}")
            }

            lower.startsWith("opencode ") -> {
                out("sending prompt to OpenCode…")
                val prompt = shellQ(arg)
                val r = Shell.termux(
                    "proot-distro login ubuntu -- /bin/bash -lc 'opencode run $prompt' 2>&1",
                    300_000
                )
                out("rc=${r.rc}")
                out(r.out.ifBlank { "(no output)" }.take(4000))
                if (r.err.isNotBlank()) out("stderr: ${r.err.take(500)}")
            }

            lower == "projects" -> {
                val roots = listOf("/storage/emulated/0/Projects", "/storage/emulated/0/Download", "/storage/emulated/0/Documents", SessionState.dir)
                for (root in roots) {
                    try {
                        val hits = Fs.search(root, "", 0).ifEmpty { null }
                    } catch (_: Exception) {}
                }
                for (root in roots) {
                    try {
                        val dirs = Fs.list(root).filter { it.isDir }
                        val markers = listOf(".git", "build.gradle", "build.gradle.kts", "package.json", "requirements.txt", "pom.xml", "settings.gradle")
                        for (d in dirs) {
                            val found = markers.firstOrNull { m -> Fs.exists(d.path + "/" + m) }
                            if (found != null) out("📦 ${d.path}  (${found})")
                        }
                    } catch (_: Exception) {}
                }
                out("scan done (roots: ${roots.joinToString()})")
            }

            else -> out("unknown request — type 'help'")
        }
    }

    private fun shellQ(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
