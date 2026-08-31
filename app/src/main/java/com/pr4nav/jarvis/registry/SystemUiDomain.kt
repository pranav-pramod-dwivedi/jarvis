package com.pr4nav.jarvis.registry

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.pr4nav.jarvis.*
import com.pr4nav.jarvis.capabilities.DeviceCapability
import com.pr4nav.jarvis.gui.JarvisGuiRenderer
import com.pr4nav.jarvis.memory.JarvisMemoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SystemUiDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        // Clock & Date
        CapabilityDef(
            id = "clock.time",
            category = "clock",
            name = "Get Current Time",
            description = "Report current local time formatted in hours and minutes",
            aliases = listOf("what time is it", "current time", "what's the time", "tell me the time", "time please", "time"),
            execute = { _, _ ->
                val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                CapabilityExecutionResult.ok("⏰ The current time is $timeStr.")
            }
        ),

        CapabilityDef(
            id = "clock.date",
            category = "clock",
            name = "Get Current Date",
            description = "Report today's full date and day of week",
            aliases = listOf("what's the date", "what is today's date", "today's date", "current date", "what date is it"),
            execute = { _, _ ->
                val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
                CapabilityExecutionResult.ok("📅 Today is $dateStr.")
            }
        ),

        CapabilityDef(
            id = "clock.alarm.set",
            category = "clock",
            name = "Set Clock Alarm",
            description = "Schedule an alarm via Android AlarmClock provider",
            aliases = listOf("set an alarm for", "set alarm for", "wake me up at", "alarm at"),
            optionalParams = listOf("hour", "minute", "label"),
            risk = RiskLevel.MEDIUM,
            execute = { _, params ->
                val hour = (params["hour"] as? Number)?.toInt() ?: 7
                val minute = (params["minute"] as? Number)?.toInt() ?: 0
                val label = (params["label"] as? String) ?: "Alarm"
                val res = DeviceCapability.setAlarm(hour, minute, label)
                if (res.success) CapabilityExecutionResult.ok("⏰ Alarm set for %02d:%02d ($label).".format(hour, minute))
                else CapabilityExecutionResult.fail("Alarm error: ${res.error}")
            }
        ),

        CapabilityDef(
            id = "clock.timer.set",
            category = "clock",
            name = "Set Countdown Timer",
            description = "Start a countdown timer via system clock app",
            aliases = listOf("set a timer for", "set timer for", "start a timer for", "timer for"),
            optionalParams = listOf("seconds", "minutes", "label"),
            risk = RiskLevel.MEDIUM,
            execute = { _, params ->
                val mins = (params["minutes"] as? Number)?.toInt()
                val seconds = (params["seconds"] as? Number)?.toInt() ?: ((mins ?: 1) * 60)
                val label = (params["label"] as? String) ?: "Timer"
                val res = DeviceCapability.setTimer(seconds, label)
                if (res.success) CapabilityExecutionResult.ok("⏱️ Timer started for ${seconds / 60}m ($label).")
                else CapabilityExecutionResult.fail("Timer error: ${res.error}")
            }
        ),

        // Camera
        CapabilityDef(
            id = "camera.open",
            category = "camera",
            name = "Open Camera",
            description = "Launch default Android camera app",
            aliases = listOf("open camera", "take a picture", "take a photo", "launch camera"),
            execute = { ctx, _ ->
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("📷 Camera launched.")
            }
        ),

        CapabilityDef(
            id = "camera.video",
            category = "camera",
            name = "Record Video",
            description = "Launch camera in video capture mode",
            aliases = listOf("record video", "record a video"),
            execute = { ctx, _ ->
                val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("🎥 Video camera launched.")
            }
        ),

        // Clipboard
        CapabilityDef(
            id = "clipboard.read",
            category = "clipboard",
            name = "Read Clipboard",
            description = "Read current text from Android system clipboard",
            aliases = listOf("read clipboard", "what's on my clipboard", "clipboard content"),
            execute = { ctx, _ ->
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cb.primaryClip?.getItemAt(0)?.text?.toString()
                if (text.isNullOrBlank()) CapabilityExecutionResult.ok("📋 Clipboard is empty.")
                else CapabilityExecutionResult.ok("📋 Clipboard: \"$text\"")
            }
        ),

        CapabilityDef(
            id = "clipboard.write",
            category = "clipboard",
            name = "Copy to Clipboard",
            description = "Copy given text to Android system clipboard",
            aliases = listOf("copy to clipboard", "copy this"),
            requiredParams = listOf("text"),
            execute = { ctx, params ->
                val text = (params["text"] as? String) ?: ""
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
                CapabilityExecutionResult.ok("📋 Copied to clipboard: \"$text\"")
            }
        ),

        // System Settings
        CapabilityDef(
            id = "settings.open",
            category = "settings",
            name = "Open Settings",
            description = "Open main system Android Settings screen",
            aliases = listOf("open settings", "system settings", "settings"),
            execute = { ctx, _ ->
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("⚙️ Settings opened.")
            }
        ),

        CapabilityDef(
            id = "settings.wifi",
            category = "settings",
            name = "Open Wi-Fi Settings",
            description = "Open Android Wi-Fi configuration settings",
            aliases = listOf("open wifi settings", "wifi settings"),
            execute = { ctx, _ ->
                ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("📶 Wi-Fi settings opened.")
            }
        ),

        CapabilityDef(
            id = "settings.bluetooth",
            category = "settings",
            name = "Open Bluetooth Settings",
            description = "Open Android Bluetooth settings",
            aliases = listOf("open bluetooth settings", "bluetooth settings"),
            execute = { ctx, _ ->
                ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("🔹 Bluetooth settings opened.")
            }
        ),

        CapabilityDef(
            id = "settings.display",
            category = "settings",
            name = "Open Display Settings",
            description = "Open display and brightness settings",
            aliases = listOf("display settings", "open display settings", "screen settings"),
            execute = { ctx, _ ->
                ctx.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("🔆 Display settings opened.")
            }
        ),

        // Memory
        CapabilityDef(
            id = "memory.save",
            category = "memory",
            name = "Remember Fact / Context",
            description = "Store persistent fact into JARVIS long-term memory",
            aliases = listOf("remember that", "remember this", "save note", "remember"),
            optionalParams = listOf("key", "value", "content"),
            backend = BackendType.INTERNAL_STORE,
            execute = { ctx, params ->
                val key = (params["key"] as? String) ?: "fact"
                val value = (params["value"] as? String) ?: (params["content"] as? String) ?: ""
                JarvisMemoryStore.remember(ctx, key, value)
                CapabilityExecutionResult.ok("🧠 Remembered: $key = \"$value\".")
            }
        ),

        CapabilityDef(
            id = "memory.list",
            category = "memory",
            name = "List Stored Memory",
            description = "Retrieve all persistent facts stored in JARVIS memory",
            aliases = listOf("what do you remember", "list memory", "show memory", "view notes"),
            backend = BackendType.INTERNAL_STORE,
            execute = { ctx, _ ->
                val all = JarvisMemoryStore.getAll(ctx)
                if (all.isEmpty()) CapabilityExecutionResult.ok("🧠 No facts saved in memory yet.")
                else CapabilityExecutionResult.ok("🧠 Memory (${all.size} entries):\n${all.take(5).joinToString("\n") { "• ${it.key}: ${it.value}" }}")
            }
        ),

        // GUI Navigation Parity
        CapabilityDef(
            id = "gui.open.files",
            category = "gui",
            name = "Open File Browser",
            description = "Navigate to the JARVIS File Manager view",
            aliases = listOf("open file manager", "show file browser", "file manager"),
            execute = { ctx, _ ->
                ctx.startActivity(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("📁 File Browser opened.")
            }
        ),

        CapabilityDef(
            id = "gui.open.terminal",
            category = "gui",
            name = "Open Terminal GUI",
            description = "Navigate to the interactive Terminal screen",
            aliases = listOf("open terminal", "terminal", "show terminal"),
            execute = { ctx, _ ->
                ctx.startActivity(Intent(ctx, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("💻 Terminal view opened.")
            }
        ),

        CapabilityDef(
            id = "gui.open.dashboard",
            category = "gui",
            name = "Open System Dashboard",
            description = "Display live HUD HTML system metrics dashboard",
            aliases = listOf("show dashboard", "open dashboard", "system dashboard", "cpu dashboard"),
            execute = { ctx, _ ->
                JarvisGuiRenderer.showSystemDashboard(ctx)
                CapabilityExecutionResult.ok("📊 System Dashboard rendered.")
            }
        ),

        CapabilityDef(
            id = "gui.open.diagnostics",
            category = "gui",
            name = "Open System Diagnostics",
            description = "Navigate to the real-time system status and diagnostics screen",
            aliases = listOf("open diagnostics", "run diagnostics", "system status", "diagnostics"),
            execute = { ctx, _ ->
                ctx.startActivity(Intent(ctx, DiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("⚡ Diagnostics opened.")
            }
        ),

        CapabilityDef(
            id = "gui.open.capabilities",
            category = "gui",
            name = "Open Capability Inspector",
            description = "Navigate to the interactive Capability Registry inspector and live testing suite",
            aliases = listOf("open capabilities", "show capabilities", "capability registry", "capabilities inspector", "tools list", "capabilities"),
            execute = { ctx, _ ->
                ctx.startActivity(Intent(ctx, CapabilitiesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("🛠️ Capability Registry Inspector opened.")
            }
        )
    )
}
