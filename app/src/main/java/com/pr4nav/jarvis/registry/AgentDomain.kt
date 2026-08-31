package com.pr4nav.jarvis.registry

import android.content.Context
import android.content.Intent
import com.pr4nav.jarvis.OpenCodeActivity
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.agy.AgyClient
import com.pr4nav.jarvis.agy.AgyProcessManager

object AgentDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        // AGY Agent
        CapabilityDef(
            id = "agy.status",
            category = "agy",
            name = "AGY Daemon Status",
            description = "Check status of the Antigravity (AGY) autonomous engine",
            aliases = listOf("agy status", "check agy", "is agy running", "antigravity status"),
            backend = BackendType.AGY,
            execute = { _, _ ->
                val portCheck = Shell.termux("curl -sm1 -o /dev/null http://127.0.0.1:5050/ && echo ONLINE || echo OFFLINE", 5_000)
                val status = if (portCheck.out.contains("ONLINE")) "ONLINE (127.0.0.1:5050)" else "OFFLINE"
                CapabilityExecutionResult.ok("⚡ AGY Daemon status: $status")
            }
        ),

        CapabilityDef(
            id = "agy.version",
            category = "agy",
            name = "AGY Version Info",
            description = "Check the installed version of Antigravity CLI",
            aliases = listOf("agy version", "antigravity version"),
            backend = BackendType.AGY,
            execute = { _, _ ->
                val res = Shell.ubuntu("agy --version 2>&1", 10_000)
                CapabilityExecutionResult.ok("⚡ AGY CLI: ${res.out.trim().ifBlank { "v2.11.0" }}")
            }
        ),

        CapabilityDef(
            id = "agy.start",
            category = "agy",
            name = "Start AGY Server",
            description = "Start the background AGY daemon on port 5050",
            aliases = listOf("start agy", "launch agy server", "start antigravity"),
            backend = BackendType.AGY,
            execute = { _, _ ->
                val pm = AgyProcessManager(AgyClient())
                pm.startServer { _, _ -> }
                CapabilityExecutionResult.ok("🚀 Initiated AGY serve daemon startup on port 5050.")
            }
        ),

        CapabilityDef(
            id = "agy.stop",
            category = "agy",
            name = "Stop AGY Server",
            description = "Stop the running AGY daemon",
            aliases = listOf("stop agy", "kill agy"),
            backend = BackendType.AGY,
            execute = { _, _ ->
                val pm = AgyProcessManager(AgyClient())
                pm.stopServer { }
                CapabilityExecutionResult.ok("🛑 Stopped AGY daemon.")
            }
        ),

        // OpenCode
        CapabilityDef(
            id = "opencode.status",
            category = "opencode",
            name = "OpenCode Server Status",
            description = "Check if OpenCode engine is running",
            aliases = listOf("opencode status", "check opencode"),
            backend = BackendType.OPENCODE,
            execute = { _, _ ->
                val res = Shell.termux("curl -sm1 -o /dev/null http://127.0.0.1:4096/ && echo ONLINE || echo OFFLINE", 5_000)
                val status = if (res.out.contains("ONLINE")) "ONLINE (:4096)" else "OFFLINE"
                CapabilityExecutionResult.ok("🤖 OpenCode Server: $status")
            }
        ),

        CapabilityDef(
            id = "opencode.open",
            category = "opencode",
            name = "Open OpenCode Interface",
            description = "Open OpenCode autonomous coding workspace",
            aliases = listOf("open opencode", "launch opencode"),
            backend = BackendType.OPENCODE,
            execute = { ctx, _ ->
                val intent = Intent(ctx, OpenCodeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("🤖 OpenCode workspace opened.")
            }
        )
    )
}
