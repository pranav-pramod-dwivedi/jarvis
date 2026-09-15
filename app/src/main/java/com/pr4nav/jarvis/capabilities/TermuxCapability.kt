package com.pr4nav.jarvis.capabilities

import android.content.pm.PackageManager
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.TermuxBridge
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject

object TermuxCapability : Capability {

    override val name = "termux"

    fun installed(): Boolean = try {
        Capabilities.require().packageManager.getPackageInfo(TermuxBridge.TERMUX_PKG, 0)
        true
    } catch (_: Exception) { false }

    fun reachable(): Boolean = if (installed() && permittedBridge()) Shell.termuxReachable() else false

    private fun permittedBridge(): Boolean = TermuxBridge.hasPermission()

    fun run(command: String, timeoutMs: Long): CapabilityResult {
        if (!reachable())
            return CapabilityResult.fail(
                "Termux bridge unreachable (installed=${installed()} permitted=${permitted()})"
            )
        val r = Shell.termux(command, timeoutMs)
        return CapabilityResult.ok(
            JSONObject().put("rc", r.rc ?: -1)
                .put("stdout", r.out.take(30_000))
                .put("stderr", r.err.take(5_000)).toString(),
            "ms" to r.ms.toString(), "via" to r.via
        )
    }

    override fun available(): Boolean = installed()
    override fun permitted(): Boolean = reachable()

    override fun status(): String = when {
        !installed() -> "✗ Termux — not installed"
        !TermuxBridge.hasPermission() -> "△ Termux — RUN_COMMAND permission missing"
        !Shell.termuxReachable() -> "△ Termux — installed but bridge did not answer"
        else -> "✓ Termux — bridge live"
    }

    override fun tools() = listOf(
        ToolDef("termux.status", "Termux install/permission/bridge state", "{}", null,
            {
                CapabilityResult.ok(
                    JSONObject().put("installed", installed())
                        .put("permitted", permitted())
                        .put("bridgeReachable", reachable()).toString()
                ).envelope()
            }),
        ToolDef("termux.run", "Run one shell command inside Termux (real Linux env)", """{"command":"uname -a","timeoutMs":30000}""",
            { if (Capabilities.app != null && !reachable()) "Termux bridge unreachable (installed=${installed()} permitted=${permitted()})" else null },
            { a ->
                if (!reachable()) CapabilityResult.fail("Termux bridge unreachable").envelope()
                else run(a.getString("command"), a.optLong("timeoutMs", 30_000).coerceIn(1_000, 300_000)).envelope()
            })
    )
}
