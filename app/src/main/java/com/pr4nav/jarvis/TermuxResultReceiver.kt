package com.pr4nav.jarvis

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

data class TermuxResult(
    val reqId: Int = 0,
    val label: String,
    val cmd: String,
    val stdout: String?,
    val stderr: String?,
    val exitCode: Int?,
    val err: Int?,
    val errmsg: String?,
    val internalError: String? = null
)

fun TermuxResult.toJson(): String = JSONObject().apply {
    put("label", label); put("cmd", cmd)
    put("stdout", stdout ?: ""); put("stderr", stderr ?: "")
    put("rc", exitCode ?: -999); put("err", err ?: -999)
    put("errmsg", errmsg ?: ""); put("internal_error", internalError ?: "")
}.toString()

object ResultBus {
    const val EXTRA_LABEL = "jarvis_label"
    const val EXTRA_CMD = "jarvis_cmd"
    const val EXTRA_REQ_ID = "jarvis_req_id"
    const val KEY_RESULT_BUNDLE = "result"
    const val KEY_STDOUT = "stdout"
    const val KEY_STDERR = "stderr"
    const val KEY_EXIT_CODE = "exitCode"
    const val KEY_ERR = "err"
    const val KEY_ERRMSG = "errmsg"

    val listeners = CopyOnWriteArrayList<(TermuxResult) -> Unit>()
}

/** Receiver targeted by the PendingIntents we hand to Termux. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(ResultBus.EXTRA_LABEL) ?: "?"
        val cmd = intent.getStringExtra(ResultBus.EXTRA_CMD) ?: ""
        val reqId = intent.getIntExtra(ResultBus.EXTRA_REQ_ID, 0)
        val b: Bundle? = intent.getBundleExtra(ResultBus.KEY_RESULT_BUNDLE)
        val r = if (b == null) {
            TermuxResult(reqId, label, cmd, null, null, null, null, null,
                internalError = "result bundle missing")
        } else {
            TermuxResult(
                reqId, label, cmd,
                b.getString(ResultBus.KEY_STDOUT),
                b.getString(ResultBus.KEY_STDERR),
                if (b.containsKey(ResultBus.KEY_EXIT_CODE)) b.getInt(ResultBus.KEY_EXIT_CODE) else null,
                if (b.containsKey(ResultBus.KEY_ERR)) b.getInt(ResultBus.KEY_ERR) else null,
                b.getString(ResultBus.KEY_ERRMSG) ?: ""
            )
        }
        Log.i("JARVIS", r.toJson())
        TermuxBridge.deliver(r)
        ResultBus.listeners.forEach { l -> try { l(r) } catch (_: Exception) {} }
    }
}

/**
 * Singleton bridge to Termux RUN_COMMAND with synchronous execute().
 * Used by the UI, the agent, and the filesystem layer alike.
 */
object TermuxBridge {
    const val TERMUX_PKG = "com.termux"
    const val TERMUX_SVC = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    const val SH = "/data/data/com.termux/files/usr/bin/bash"
    const val HOME = "/data/data/com.termux/files/home"
    const val PERM = "com.termux.permission.RUN_COMMAND"

    private var ctx: Context? = null
    private val pending = ConcurrentHashMap<Int, SynchronousQueue<TermuxResult>>()
    private val idGen = java.util.concurrent.atomic.AtomicInteger(9000)

    fun init(context: Context) { ctx = context.applicationContext }

    fun hasPermission(): Boolean =
        ctx?.let { androidx.core.content.ContextCompat.checkSelfPermission(it, PERM) } == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun deliver(r: TermuxResult) { pending[r.reqId]?.offer(r) }

    private fun isUnitTest(): Boolean {
        return try {
            Class.forName("org.junit.Test") != null
        } catch (_: Throwable) {
            false
        }
    }

    /** Blocking execute. Returns null on timeout/dispatch failure. */
    fun execute(label: String, command: String, timeoutMs: Long = 30_000, background: Boolean = true, stdin: String? = null): TermuxResult? {
        val c = ctx ?: return null
        if (isUnitTest()) return null
        if (!hasPermission()) return null
        val id = idGen.incrementAndGet()
        val q = SynchronousQueue<TermuxResult>()
        pending[id] = q
        try {
            val piFlags = PendingIntent.FLAG_ONE_SHOT or
                (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val resultIntent = Intent(c, TermuxResultReceiver::class.java)
                .putExtra(ResultBus.EXTRA_LABEL, label)
                .putExtra(ResultBus.EXTRA_CMD, command)
                .putExtra(ResultBus.EXTRA_REQ_ID, id)
            val pi = PendingIntent.getBroadcast(c, id, resultIntent, piFlags)
            val i = Intent()
                .setClassName(TERMUX_PKG, TERMUX_SVC)
                .setAction(ACTION_RUN_COMMAND)
                .putExtra(EXTRA_PATH, SH)
                .putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                .putExtra(EXTRA_WORKDIR, HOME)
                .putExtra(EXTRA_BACKGROUND, background)
                .putExtra(EXTRA_COMMAND_LABEL, label)
                .putExtra(EXTRA_PENDING_INTENT, pi)
            stdin?.let { i.putExtra(EXTRA_STDIN, it) }
            try {
                c.startService(i)
            } catch (_: Exception) {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    c.startForegroundService(i)
                }
            }
            return q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            return TermuxResult(id, label, command, null, null, null, null, null,
                internalError = "dispatch failed: ${e.message}")
        } finally {
            pending.remove(id)
        }
    }
}

/** Unified shell runner: Termux (Ubuntu proot Linux env), local Android sh, root su. */
object Shell {
    data class Res(val out: String, val err: String, val rc: Int?, val ms: Long, val timedOut: Boolean, val via: String)

    fun wrapUbuntu(command: String): String {
        val trimmed = command.trim()
        if (trimmed.startsWith("proot-distro login")) return command
        val initRule = "DIR=\"/root\"; if [ ! -w \"/root\" ]; then DIR=\"\${HOME:-/tmp}\"; fi; mkdir -p \"\$DIR\" 2>/dev/null; if [ ! -f \"\$DIR/GEMINI.md\" ] && [ -w \"\$DIR\" ]; then echo '# Termux Agent Directives\\nYou are an autonomous AI Agent operating directly inside Termux on Android.\\nWhen asked to open apps or URLs, execute: am start -a android.intent.action.VIEW -d [url] or termux-open-url [url].\\nExecute commands directly with run_command.' > \"\$DIR/GEMINI.md\" 2>/dev/null || true; fi; "
        val fullCmd = "export PATH=\"/root/.local/bin:/usr/local/bin:\$PATH\"; $initRule $command"
        val escaped = fullCmd.replace("'", "'\\''")
        return "export PATH=\"/data/data/com.termux/files/usr/bin:/system/bin:\$PATH\"; " +
               "export PREFIX=\"/data/data/com.termux/files/usr\"; " +
               "export HOME=\"/data/data/com.termux/files/home\"; " +
               "unset LD_PRELOAD; " +
               "if [ -x /data/data/com.termux/files/usr/bin/proot-distro ] || command -v proot-distro >/dev/null 2>&1; then " +
               "  /data/data/com.termux/files/usr/bin/proot-distro login ubuntu -- /bin/bash -c '$escaped' 2>/dev/null < /dev/null; " +
               "elif [ -x /data/data/com.termux/files/usr/bin/bash ]; then " +
               "  /data/data/com.termux/files/usr/bin/bash -c '$escaped' 2>/dev/null < /dev/null; " +
               "else " +
               "  sh -c '$escaped' 2>/dev/null < /dev/null; " +
               "fi"
    }

    fun ubuntu(command: String, timeoutMs: Long = 30_000): Res {
        return termux(wrapUbuntu(command), timeoutMs, inUbuntu = false, viaName = "ubuntu")
    }

    /**
     * Autonomous AGY CLI execution inside PRoot Ubuntu.
     * Uses Gemini 3.7 Flash (Low) without requiring an API key.
     * Appends < /dev/null to prevent proot terminal hangs and tries Termux first,
     * falling back smoothly to root su.
     */
    fun agy(prompt: String, model: String? = null, timeoutMs: Long = 45_000): Res {
        val escapedPrompt = prompt.replace("\"", "\\\"").replace("'", "'\\''")
        val sanitizedModel = com.pr4nav.jarvis.agy.AgyManager.sanitizeModel(model)
        val agyCmd = "agy -p \"$escapedPrompt\" --continue --dangerously-skip-permissions --model \"$sanitizedModel\""
        val wrapped = wrapUbuntu(agyCmd)

        // Try TermuxBridge IPC first
        val termuxRes = termux(wrapped, timeoutMs, inUbuntu = false, viaName = "termux-agy")
        if (termuxRes.rc == 0 && termuxRes.out.isNotBlank()) {
            return termuxRes
        }

        // Fallback to root su directly if TermuxBridge timed out or is unavailable
        val rootRes = root(wrapped, timeoutMs)
        return if (rootRes.rc == 0 && rootRes.out.isNotBlank()) {
            Res(rootRes.out, rootRes.err, rootRes.rc, rootRes.ms, rootRes.timedOut, "root-agy")
        } else {
            // Return whichever had more useful info
            if (termuxRes.out.isNotBlank()) termuxRes else rootRes
        }
    }

    fun termuxRaw(command: String, timeoutMs: Long = 30_000): Res {
        return termux(command, timeoutMs, inUbuntu = false, viaName = "termux-host")
    }

    fun termux(command: String, timeoutMs: Long = 30_000, inUbuntu: Boolean = true, viaName: String? = null): Res {
        val t0 = System.currentTimeMillis()
        val finalCmd = if (inUbuntu) wrapUbuntu(command) else command
        val via = viaName ?: (if (inUbuntu) "ubuntu" else "termux")
        val r = TermuxBridge.execute("shell", finalCmd, timeoutMs) ?: return Res("", "bridge unavailable/timeout", null, System.currentTimeMillis() - t0, true, via)
        val ok = r.internalError == null && (r.err ?: -1) == -1
        return Res(
            r.stdout ?: "", (r.stderr ?: "") + if (r.internalError != null) " ${r.internalError}" else "",
            if (ok) r.exitCode else -1, System.currentTimeMillis() - t0, !ok && r.internalError?.contains("TIMEOUT") == true, via
        )
    }

    fun local(command: String, timeoutMs: Long = 15_000): Res {
        val t0 = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder("sh", "-c", command).start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) { p.destroyForcibly(); Res("", "timeout", 124, System.currentTimeMillis() - t0, true, "local") }
            else Res(p.inputStream.bufferedReader().readText(), p.errorStream.bufferedReader().readText(), p.exitValue(), System.currentTimeMillis() - t0, false, "local")
        } catch (e: Exception) { Res("", e.message ?: "failed", -1, System.currentTimeMillis() - t0, false, "local") }
    }

    fun root(command: String, timeoutMs: Long = 45_000): Res {
        val t0 = System.currentTimeMillis()
        val suBin = listOf("/product/bin/su", "/system/bin/su", "/system/xbin/su", "/sbin/su").firstOrNull { File(it).exists() }
        if (suBin == null) {
            return Res("", "root su binary unavailable", -1, System.currentTimeMillis() - t0, false, "root")
        }
        return try {
            val p = ProcessBuilder(suBin, "-c", command).start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) { p.destroyForcibly(); Res("", "timeout", 124, System.currentTimeMillis() - t0, true, "root") }
            else {
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                if (err.contains("not found") || err.contains("permission denied")) Res(out, "ERR: $err", 1, System.currentTimeMillis() - t0, false, "root")
                else Res(out, err, p.exitValue(), System.currentTimeMillis() - t0, false, "root")
            }
        } catch (e: Exception) { Res("", "ERR: ${e.message}", -1, System.currentTimeMillis() - t0, false, "root") }
    }

    @Volatile private var reachableCache: Boolean? = null
    fun termuxReachable(): Boolean {
        reachableCache?.let { return it }
        val r = termux("echo OK", 10_000)
        val ok = r.rc == 0 && r.out.contains("OK")
        reachableCache = ok
        return ok
    }
}

/** Shared state between file manager UI and the agent — ONE real filesystem. */
object SessionState {
    @Volatile var dir: String = "/storage/emulated/0/JARVIS/workspace"
}

/**
 * Safety guard for user-facing consoles (Commander/Terminal/agent `run`).
 * VERIFY/OPEN only: destructive operations are refused with a real reason.
 * Internal bootstrap commands do NOT pass through this guard (they are
 * owned, audited, and idempotent — not arbitrary user input).
 */
object CmdGuard {
    private val patterns = listOf(
        Regex("""\brm\b"""), Regex("""\brmdir\b"""), Regex("""\bunlink\b"""),
        Regex("""\bshred\b"""), Regex("""\bdd\b"""), Regex("""\bmkfs"""), Regex("""\btruncate\b"""),
        Regex("""-delete\b"""), Regex("""\bkill\b"""), Regex("""\bpkill\b"""), Regex("""\bkillall\b"""),
        Regex("""\breboot\b"""), Regex("""\bshutdown\b"""), Regex("""\bpoweroff\b"""),
        Regex("""proot-distro\s+remove"""), Regex("""\bapt(-get)?\s+(remove|purge|autoremove)\b"""),
        Regex("""\bpkg\s+uninstall\b"""), Regex("""\bpip3?\s+uninstall\b"""), Regex("""\bnpm\s+(uninstall|rm)\b"""),
        Regex("""\bchmod\s+(-[Rf]+\s+)*0?0?0\s"""), Regex("""\bchown\s+(-R\b)"""),
        Regex("""git\s+push\s+[^;|]*--force"""), Regex("""\b(parted|fdisk|mkswap)\b"""),
        Regex(""">\s*/dev/"""), Regex("""\bof=/dev/""")
    )

    /** Returns a refusal reason if the command is destructive, null if allowed. */
    fun check(command: String): String? {
        val reason = patterns.firstOrNull { it.containsMatchIn(command) }?.pattern
            ?: return null
        return "Blocked: matches destructive pattern /$reason/\n" +
            "This console is VERIFY/OPEN only — no deletion, no process kills, no package removal.\n" +
            "Use the Termux app directly for administrative work."
    }
}
