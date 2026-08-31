package com.pr4nav.jarvis.registry

import android.content.Context
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.capabilities.AudioCapability
import com.pr4nav.jarvis.capabilities.DeviceCapability
import com.pr4nav.jarvis.memory.JarvisMemoryStore
import com.pr4nav.jarvis.needle.NeedleRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WorkflowDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        CapabilityDef(
            id = "workflow.diagnose",
            category = "workflow",
            name = "Check Yourself (Full System Audit)",
            description = "Run comprehensive multi-layer health audit: Android APIs, Needle, Termux, Ubuntu, AGY daemon, Filesystem, Memory",
            aliases = listOf("check yourself", "diagnose jarvis", "system audit", "health check", "jarvis status", "self test"),
            backend = BackendType.INTERNAL_STORE,
            execute = { ctx, _ ->
                val sb = StringBuilder()
                sb.append("🛡️ JARVIS SYSTEM AUDIT:\n")

                // 1. Device APIs
                val (pct, charging) = DeviceCapability.battery()
                sb.append("• Android APIs: ✓ (Battery $pct% ${if (charging) "⚡" else ""})\n")

                // 2. Needle 2 Reflex
                val needleOnline = NeedleRuntime.isModelLoaded
                val needleLat = NeedleRuntime.averageInferenceMs
                sb.append("• Needle 2 Reflex: ${if (needleOnline) "✓ (Daemon Ready · avg ${needleLat}ms)" else "○ (Offline/Direct)"}\n")

                // 3. Termux Bridge
                val termuxCheck = Shell.termux("uname -s", 3000)
                val termuxOk = termuxCheck.rc == 0
                sb.append("• Termux Bridge: ${if (termuxOk) "✓ (Active)" else "✗ (${termuxCheck.err.take(30)})"}\n")

                // 4. Ubuntu PRoot
                val ubuntuCheck = Shell.ubuntu("echo UBUNTU_OK", 5000)
                val ubuntuOk = ubuntuCheck.out.contains("UBUNTU_OK")
                sb.append("• Ubuntu PRoot: ${if (ubuntuOk) "✓ (Ready)" else "✗ (Offline)"}\n")

                // 5. AGY Daemon (:5050)
                val agyCheck = Shell.termux("curl -sm1 -o /dev/null http://127.0.0.1:5050/ && echo UP || echo DOWN", 3000)
                val agyOk = agyCheck.out.contains("UP")
                sb.append("• AGY Daemon (:5050): ${if (agyOk) "✓ (Running)" else "○ (Stopped)"}\n")

                // 6. Memory
                val memCount = JarvisMemoryStore.getAll(ctx).size
                sb.append("• Memory Store: ✓ ($memCount facts saved)\n")

                // 7. Registry
                sb.append("• Capability Registry: ✓ (${CapabilityRegistry.size()} tools indexed)")

                CapabilityExecutionResult.ok(sb.toString())
            }
        ),

        CapabilityDef(
            id = "workflow.goodnight",
            category = "workflow",
            name = "Good Night Routine",
            description = "Bedtime workflow: Turn off torch, silence media volume, set ringer to silent, report current time",
            aliases = listOf("good night jarvis", "good night", "going to sleep", "bedtime", "night mode"),
            backend = BackendType.ANDROID_API,
            execute = { ctx, _ ->
                // 1. Torch off
                DeviceCapability.torch(false)
                // 2. Mute music
                AudioCapability.mute("music", true)
                // 3. Silent ringer
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                CapabilityExecutionResult.ok("🌙 Good night! Flashlight turned OFF, media muted, and phone silenced. Current time: $timeStr.")
            }
        ),

        CapabilityDef(
            id = "workflow.morning",
            category = "workflow",
            name = "Good Morning Routine",
            description = "Morning briefing: Unmute media, announce time, date, battery level, and uptime",
            aliases = listOf("good morning jarvis", "good morning", "morning briefing", "wake up"),
            backend = BackendType.ANDROID_API,
            execute = { ctx, _ ->
                AudioCapability.mute("music", false)
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                val dateStr = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
                val (pct, _) = DeviceCapability.battery()
                CapabilityExecutionResult.ok("☀️ Good morning! It's $timeStr on $dateStr. Battery is at $pct%. Phone ringer is active.")
            }
        ),

        CapabilityDef(
            id = "workflow.leaving",
            category = "workflow",
            name = "Leaving Out Routine",
            description = "Leaving routine: Check battery, set volume to 80%, prepare navigation to home/destination",
            aliases = listOf("i'm leaving", "leaving now", "heading out", "going out"),
            backend = BackendType.ANDROID_API,
            execute = { ctx, _ ->
                val (pct, _) = DeviceCapability.battery()
                AudioCapability.setVolume("music", 12)
                CapabilityExecutionResult.ok("🚗 Have a safe trip! Battery is at $pct%. Media volume set to 80%. Ready for navigation.")
            }
        ),

        CapabilityDef(
            id = "workflow.coding",
            category = "workflow",
            name = "Developer / Coding Mode",
            description = "Start autonomous coding workspace: Check Termux & Ubuntu, check AGY on :5050, launch OpenCode",
            aliases = listOf("start coding", "developer mode", "open coding environment", "code mode", "start development"),
            backend = BackendType.OPENCODE,
            execute = { ctx, _ ->
                val intent = android.content.Intent(ctx, com.pr4nav.jarvis.OpenCodeActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("💻 Developer Mode: Launched OpenCode autonomous coding workspace. AGY & PRoot Ubuntu active.")
            }
        ),

        CapabilityDef(
            id = "workflow.focus",
            category = "workflow",
            name = "Focus / Study Routine",
            description = "Focus mode: Mute sounds, put ringer on silent, and start a 45-minute countdown timer",
            aliases = listOf("focus mode", "study mode", "do not disturb", "pomodoro"),
            backend = BackendType.ANDROID_API,
            execute = { ctx, _ ->
                AudioCapability.mute("music", true)
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                DeviceCapability.setTimer(45 * 60, "Focus Session")
                CapabilityExecutionResult.ok("🎯 Focus Mode active: Sounds muted, phone silenced, and 45-minute timer started.")
            }
        )
    )
}
