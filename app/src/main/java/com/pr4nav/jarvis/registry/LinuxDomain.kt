package com.pr4nav.jarvis.registry

import android.content.Context
import com.pr4nav.jarvis.Shell

object LinuxDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        // Termux Host
        CapabilityDef(
            id = "termux.execute",
            category = "terminal",
            name = "Execute Termux Command",
            description = "Run a shell command directly in the native Termux host environment",
            aliases = listOf("run termux", "termux execute", "shell command"),
            requiredParams = listOf("command"),
            risk = RiskLevel.MEDIUM,
            backend = BackendType.TERMUX,
            execute = { _, params ->
                val cmd = (params["command"] as? String) ?: ""
                val res = Shell.termuxRaw(cmd, 30_000)
                val out = if (res.out.isNotBlank()) res.out.trim() else res.err.trim()
                if (res.rc == 0) CapabilityExecutionResult.ok("💻 Termux (rc=0):\n$out", res)
                else CapabilityExecutionResult.fail("Termux failed (rc=${res.rc}):\n$out")
            }
        ),

        CapabilityDef(
            id = "termux.pwd",
            category = "terminal",
            name = "Termux Current Directory",
            description = "Print current working directory in Termux",
            aliases = listOf("pwd", "termux pwd", "where am i in termux"),
            backend = BackendType.TERMUX,
            execute = { _, _ ->
                val res = Shell.termuxRaw("pwd", 5_000)
                CapabilityExecutionResult.ok("💻 Termux pwd: ${res.out.trim()}")
            }
        ),

        CapabilityDef(
            id = "termux.ls",
            category = "terminal",
            name = "List Termux Home",
            description = "List files in Termux home directory",
            aliases = listOf("termux ls", "ls termux"),
            backend = BackendType.TERMUX,
            execute = { _, _ ->
                val res = Shell.termuxRaw("ls -la", 10_000)
                CapabilityExecutionResult.ok("💻 Termux ls:\n${res.out.trim()}")
            }
        ),

        CapabilityDef(
            id = "termux.uname",
            category = "terminal",
            name = "Termux System Uname",
            description = "Print kernel architecture and OS info via Termux",
            aliases = listOf("uname", "termux uname", "linux version"),
            backend = BackendType.TERMUX,
            execute = { _, _ ->
                val res = Shell.termuxRaw("uname -a", 5_000)
                CapabilityExecutionResult.ok("💻 Linux Kernel: ${res.out.trim()}")
            }
        ),

        CapabilityDef(
            id = "termux.ps",
            category = "terminal",
            name = "Termux Process List",
            description = "List active user processes in Termux",
            aliases = listOf("termux ps", "running processes", "ps"),
            backend = BackendType.TERMUX,
            execute = { _, _ ->
                val res = Shell.termuxRaw("ps -A | head -n 15", 10_000)
                CapabilityExecutionResult.ok("💻 Processes:\n${res.out.trim()}")
            }
        ),

        // Ubuntu PRoot
        CapabilityDef(
            id = "ubuntu.execute",
            category = "ubuntu",
            name = "Execute Ubuntu PRoot Command",
            description = "Execute a command inside the isolated PRoot Ubuntu environment",
            aliases = listOf("run in ubuntu", "ubuntu execute"),
            requiredParams = listOf("command"),
            risk = RiskLevel.MEDIUM,
            backend = BackendType.UBUNTU,
            execute = { _, params ->
                val cmd = (params["command"] as? String) ?: ""
                val res = Shell.ubuntu(cmd, 60_000)
                val out = if (res.out.isNotBlank()) res.out.trim() else res.err.trim()
                if (res.rc == 0) CapabilityExecutionResult.ok("🐧 Ubuntu (rc=0):\n$out", res)
                else CapabilityExecutionResult.fail("Ubuntu command error (rc=${res.rc}):\n$out")
            }
        ),

        CapabilityDef(
            id = "ubuntu.status",
            category = "ubuntu",
            name = "Ubuntu Environment Status",
            description = "Verify if Ubuntu PRoot container is operational",
            aliases = listOf("ubuntu status", "is ubuntu running", "check ubuntu"),
            backend = BackendType.UBUNTU,
            execute = { _, _ ->
                val res = Shell.ubuntu("echo UBUNTU_ACTIVE && cat /etc/issue", 15_000)
                if (res.out.contains("UBUNTU_ACTIVE")) CapabilityExecutionResult.ok("🐧 Ubuntu PRoot is READY:\n${res.out.replace("UBUNTU_ACTIVE", "").trim()}")
                else CapabilityExecutionResult.fail("Ubuntu PRoot is offline or unresponsive.")
            }
        ),

        CapabilityDef(
            id = "ubuntu.disk",
            category = "ubuntu",
            name = "Ubuntu Disk Space",
            description = "Check filesystem disk space inside PRoot Linux",
            aliases = listOf("ubuntu disk", "disk space", "df -h"),
            backend = BackendType.UBUNTU,
            execute = { _, _ ->
                val res = Shell.ubuntu("df -h /", 10_000)
                CapabilityExecutionResult.ok("💾 Disk Space (Ubuntu):\n${res.out.trim()}")
            }
        )
    )
}
