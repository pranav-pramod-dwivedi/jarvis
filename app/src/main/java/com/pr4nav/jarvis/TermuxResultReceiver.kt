package com.pr4nav.jarvis

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
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

    /** Blocking execute. Returns null on timeout/dispatch failure. */
    fun execute(label: String, command: String, timeoutMs: Long = 30_000, background: Boolean = true, stdin: String? = null): TermuxResult? {
        val c = ctx ?: return null
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
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i)
            else c.startService(i)
            return q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            return TermuxResult(id, label, command, null, null, null, null, null,
                internalError = "dispatch failed: ${e.message}")
        } finally {
            pending.remove(id)
        }
    }
}

/** Unified shell runner: Termux (real Linux env), local Android sh, root su. */
object Shell {
    data class Res(val out: String, val err: String, val rc: Int?, val ms: Long, val timedOut: Boolean, val via: String)

    fun termux(command: String, timeoutMs: Long = 30_000): Res {
        val t0 = System.currentTimeMillis()
        val r = TermuxBridge.execute("shell", command, timeoutMs) ?: return Res("", "bridge unavailable/timeout", null, System.currentTimeMillis() - t0, true, "termux")
        val ok = r.internalError == null && (r.err ?: -1) == -1
        return Res(
            r.stdout ?: "", (r.stderr ?: "") + if (r.internalError != null) " ${r.internalError}" else "",
            if (ok) r.exitCode else -1, System.currentTimeMillis() - t0, !ok && r.internalError?.contains("TIMEOUT") == true, "termux"
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

    fun root(command: String, timeoutMs: Long = 15_000): Res {
        val t0 = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder("su", "-c", command).start()
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
    @Volatile var dir: String = "/storage/emulated/0"
}
