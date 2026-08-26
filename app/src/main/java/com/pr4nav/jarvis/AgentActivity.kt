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
                "Commands (relative paths resolve against shared cwd):\n" +
                    "  pwd                show shared cwd\n" +
                    "  ls [path]          list directory\n" +
                    "  open <path>        set current folder\n" +
                    "  read <path>        read a text file\n" +
                    "  edit <path>        open in JARVIS editor\n" +
                    "  write <path> <text> create/overwrite file\n" +
                    "  mkdir <path>       create folder\n" +
                    "  delete <path>      delete file/folder\n" +
                    "  find <name>        search files from current folder\n" +
                    "  search <text>      search file contents (grep) via Termux\n" +
                    "  stat <path>        file info\n" +
                    "  run <cmd>          real shell command in Termux\n" +
                    "  opencode <prompt>  ask OpenCode (needs bootstrap+auth)\n" +
                    "  projects           discover dev projects\n" +
                    "  tools              list all agent tools (capabilities)\n" +
                    "  tool <name> {json} invoke a tool, e.g. tool file.read {\"path\":\"~/notes.txt\"}"
            )

            lower == "pwd" -> out("cwd: ${SessionState.dir}")

            lower.startsWith("ls") -> {
                val p = Fs.resolve(arg.ifBlank { "." })
                val list = Fs.list(p)
                out("backend=${Fs.backendFor(p).id} · ${list.size} items")
                list.take(50).forEach { e ->
                    out((if (e.isDir) "📁 " else "📄 ") + e.name + (if (e.isDir) "/" else "  (${e.size}B)"))
                }
            }

            lower.startsWith("open ") -> {
                val p = Fs.resolve(arg)
                Fs.stat(p).let { if (!it.isDir) out("⚠ not a folder, but noted") }
                SessionState.dir = p
                out("cwd → $p (shared with file manager)")
            }

            lower.startsWith("read ") -> {
                val p = Fs.resolve(arg)
                val content = Fs.read(p)
                out("backend=${Fs.backendFor(p).id} · ${content.length} chars")
                out(content.take(2000) + if (content.length > 2000) "\n…(truncated)" else "")
            }

            lower.startsWith("edit ") -> {
                val p = Fs.resolve(arg)
                Fs.stat(p)
                runOnUiThread { startActivity(android.content.Intent(this, EditorActivity::class.java).putExtra("path", p)) }
                out("editor opened: $p")
            }

            lower.startsWith("write ") -> {
                val path = Fs.resolve(q.split(" ", limit = 3).getOrNull(1) ?: throw Fs.FsException("usage: write <path> <text>"))
                val text = q.split(" ", limit = 3).getOrNull(2) ?: ""
                Fs.write(path, text)
                out("✓ wrote ${text.length} chars → $path (backend=${Fs.backendFor(path).id})")
            }

            lower.startsWith("mkdir ") -> { val p = Fs.resolve(arg); Fs.mkdir(p); out("✓ mkdir $p") }
            lower.startsWith("delete ") -> { val p = Fs.resolve(arg); Fs.delete(p); out("✓ deleted $p") }

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
                val p = Fs.resolve(arg)
                val e = Fs.stat(p)
                out("name=${e.name}\ndir=${e.isDir}\nsize=${e.size}\nmodified=${e.modified}\nbackend=${Fs.backendFor(p).id}")
            }

            lower.startsWith("run ") -> {
                CmdGuard.check(arg)?.let { out("✗ $it"); return }
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

            lower == "tools" -> {
                com.pr4nav.jarvis.tools.JarvisToolRegistry.registerAll(this)
                out("registered tools:\n${com.pr4nav.jarvis.tools.JarvisToolRegistry.catalog()}")
            }

            lower.startsWith("tool ") -> {
                com.pr4nav.jarvis.tools.JarvisToolRegistry.registerAll(this)
                val body = arg.trim()
                val nameEnd = body.indexOfFirst { it == ' ' || it == '{' }.let { if (it < 0) body.length else it }
                val toolName = body.take(nameEnd).trim()
                val argsJson = body.drop(nameEnd).trim().ifBlank { null }
                val result = com.pr4nav.jarvis.tools.JarvisToolRegistry.execute(toolName, argsJson)
                out("tool $toolName → $result")
            }

            else -> out("unknown request — type 'help'")
        }
    }

    private fun shellQ(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
