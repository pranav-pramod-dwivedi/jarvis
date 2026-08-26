package com.pr4nav.jarvis.capabilities

import android.os.Build
import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RootCapability : Capability {

    override val name = "root"

    enum class State { CHECKING, AVAILABLE, UNAVAILABLE }

    @Volatile var state: State = State.CHECKING
        private set

    fun detect(): Boolean {
        val ok = try {
            Fs.Root.detect()
        } catch (_: Exception) { false }
        state = if (ok) State.AVAILABLE else State.UNAVAILABLE
        return ok
    }

    fun toolsEnabled(ctx: android.content.Context = Capabilities.require()): Boolean =
        ctx.getSharedPreferences("jarvis_root", android.content.Context.MODE_PRIVATE)
            .getBoolean("tools_enabled", false)

    fun setToolsEnabled(enabled: Boolean, ctx: android.content.Context = Capabilities.require()) {
        log("ROOT_TOOLS " + if (enabled) "ENABLED by user" else "DISABLED by user")
        ctx.getSharedPreferences("jarvis_root", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("tools_enabled", enabled).apply()
    }

    fun exec(command: String): CapabilityResult {
        if (state == State.CHECKING) detect()
        if (state != State.AVAILABLE)
            return CapabilityResult.fail("ROOT UNAVAILABLE — no working su on this device")
        if (!toolsEnabled())
            return CapabilityResult.fail(
                "Root tools are disabled. Enable them in JARVIS → PERMISSIONS → Root."
            )
        val r = Shell.root(command, 30_000)
        log("[cmd] $command -> rc=${r.rc} out=${r.out.take(200)} err=${r.err.take(200)}")
        return CapabilityResult.ok(
            JSONObject().put("rc", r.rc ?: -1)
                .put("stdout", r.out.take(20_000))
                .put("stderr", r.err.take(5_000)).toString(),
            "via" to r.via, "ms" to r.ms.toString()
        )
    }

    private fun logFile() =
        java.io.File(Capabilities.require().filesDir, "root_tools.log")

    @Synchronized
    private fun log(line: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            logFile().appendText("[$ts] $line\n")
        } catch (_: Exception) {}
    }

    fun recentLog(lines: Int = 30): String =
        try { logFile().readLines().takeLast(lines).joinToString("\n") } catch (_: Exception) { "" }

    override fun available(): Boolean = state == State.AVAILABLE
    override fun permitted(): Boolean = available()

    override fun status(): String = when (state) {
        State.CHECKING -> "? Root — checking…"
        State.AVAILABLE -> if (toolsEnabled()) "✓ Root — available · tools ENABLED (all commands logged)"
        else "△ Root — available · tools DISABLED (enable in Permissions)"
        State.UNAVAILABLE -> "○ Root — ROOT UNAVAILABLE (optional)"
    }

    override fun tools() = listOf(
        ToolDef("root.status", "Report root availability and tool state", "{}",
            null,
            {
                CapabilityResult.ok(JSONObject()
                    .put("state", state.name)
                    .put("toolsEnabled", if (Capabilities.app != null) toolsEnabled() else false)
                    .toString()).envelope()
            }),
        ToolDef("root.exec", "Run one shell command as root (logged; needs user-enabled root tools)", """{"command":"id"}""",
            {
                when {
                    state == State.CHECKING -> null
                    state != State.AVAILABLE -> "ROOT UNAVAILABLE"
                    Capabilities.app != null && !toolsEnabled() ->
                        "root tools disabled by user"
                    else -> null
                }
            },
            { a -> exec(a.optString("command", "")).envelope() })
    )
}
