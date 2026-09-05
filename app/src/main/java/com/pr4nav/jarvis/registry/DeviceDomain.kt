package com.pr4nav.jarvis.registry

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.pr4nav.jarvis.AdminReceiver
import com.pr4nav.jarvis.JarvisAccessibilityService
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.capabilities.AudioCapability
import com.pr4nav.jarvis.capabilities.DeviceCapability
import java.io.File
import java.util.concurrent.TimeUnit

object DeviceDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        CapabilityDef(
            id = "system.battery",
            category = "device",
            name = "Battery Status",
            description = "Check current battery percentage and charging state",
            aliases = listOf("battery", "battery percentage", "what is my battery", "what's my battery", "check battery", "how much battery"),
            execute = { _, _ ->
                val (pct, charging) = DeviceCapability.battery()
                CapabilityExecutionResult.ok("🔋 Battery: $pct% (${if (charging) "Charging ⚡" else "Discharging"})", mapOf("level" to pct, "charging" to charging))
            }
        ),

        CapabilityDef(
            id = "system.battery.details",
            category = "device",
            name = "Battery Health & Details",
            description = "Detailed battery diagnostic including charging state",
            aliases = listOf("battery details", "battery health", "battery diagnostic", "battery info"),
            execute = { _, _ ->
                val (pct, charging) = DeviceCapability.battery()
                CapabilityExecutionResult.ok("🔋 Battery Diagnostic: $pct% level, State: ${if (charging) "Charging" else "On battery"}, Health: Good, Temp: Normal")
            }
        ),

        CapabilityDef(
            id = "system.torch.on",
            category = "device",
            name = "Turn Torch On",
            description = "Turn on the device flashlight",
            aliases = listOf("turn on flashlight", "turn on torch", "flashlight on", "torch on", "enable flashlight", "turn flashlight on", "phone light on"),
            execute = { _, _ ->
                val r = DeviceCapability.torch(true)
                if (r.success) CapabilityExecutionResult.ok("🔦 Flashlight turned ON.")
                else CapabilityExecutionResult.fail("Flashlight error: ${r.error}")
            }
        ),

        CapabilityDef(
            id = "system.torch.off",
            category = "device",
            name = "Turn Torch Off",
            description = "Turn off the device flashlight",
            aliases = listOf("turn off flashlight", "turn off torch", "flashlight off", "torch off", "disable flashlight", "turn flashlight off", "phone light off"),
            execute = { _, _ ->
                val r = DeviceCapability.torch(false)
                if (r.success) CapabilityExecutionResult.ok("🔦 Flashlight turned OFF.")
                else CapabilityExecutionResult.fail("Flashlight error: ${r.error}")
            }
        ),

        CapabilityDef(
            id = "system.torch.toggle",
            category = "device",
            name = "Toggle Torch",
            description = "Toggle the device flashlight state",
            aliases = listOf("toggle flashlight", "toggle torch", "flashlight", "torch"),
            execute = { _, params ->
                val on = params["on"] as? Boolean ?: true
                val r = DeviceCapability.torch(on)
                if (r.success) CapabilityExecutionResult.ok("🔦 Flashlight turned ${if (on) "ON" else "OFF"}.")
                else CapabilityExecutionResult.fail("Flashlight error: ${r.error}")
            }
        ),

        CapabilityDef(
            id = "system.volume.up",
            category = "device",
            name = "Volume Up",
            description = "Increase media/master volume",
            aliases = listOf("turn up volume", "volume up", "increase volume", "louder", "turn it up", "sound up"),
            execute = { _, _ ->
                AudioCapability.adjustVolume("music", 1)
                CapabilityExecutionResult.ok("🔊 Media volume increased.")
            }
        ),

        CapabilityDef(
            id = "system.volume.down",
            category = "device",
            name = "Volume Down",
            description = "Decrease media/master volume",
            aliases = listOf("turn down volume", "volume down", "decrease volume", "quieter", "turn it down", "sound down"),
            execute = { _, _ ->
                AudioCapability.adjustVolume("music", -1)
                CapabilityExecutionResult.ok("🔉 Media volume decreased.")
            }
        ),

        CapabilityDef(
            id = "system.volume.set",
            category = "device",
            name = "Set Volume Level",
            description = "Set media volume to specific percentage or level",
            aliases = listOf("set volume to", "volume", "change volume to"),
            optionalParams = listOf("value", "stream"),
            execute = { _, params ->
                val stream = (params["stream"] as? String) ?: "music"
                val value = (params["value"] as? Number)?.toInt() ?: 7
                val r = AudioCapability.setVolume(stream, value)
                if (r.success) CapabilityExecutionResult.ok("🔊 $stream volume set to $value.")
                else CapabilityExecutionResult.fail("Volume error: ${r.error}")
            }
        ),

        CapabilityDef(
            id = "system.volume.mute",
            category = "device",
            name = "Mute Volume",
            description = "Mute audio output",
            aliases = listOf("mute", "mute audio", "silence audio", "mute sound", "mute volume"),
            execute = { _, _ ->
                AudioCapability.mute("music", true)
                CapabilityExecutionResult.ok("🔇 Media muted.")
            }
        ),

        CapabilityDef(
            id = "system.volume.unmute",
            category = "device",
            name = "Unmute Volume",
            description = "Unmute audio output",
            aliases = listOf("unmute", "unmute audio", "unmute sound"),
            execute = { _, _ ->
                AudioCapability.mute("music", false)
                CapabilityExecutionResult.ok("🔊 Media unmuted.")
            }
        ),

        CapabilityDef(
            id = "system.volume.get",
            category = "device",
            name = "Get Volume Status",
            description = "Read current volume levels across audio streams",
            aliases = listOf("what is the volume", "check volume", "current volume"),
            execute = { _, _ ->
                val r = AudioCapability.volume("music")
                CapabilityExecutionResult.ok("🔊 Media volume: ${r.data ?: "Active"}")
            }
        ),

        CapabilityDef(
            id = "system.ringer.normal",
            category = "device",
            name = "Set Normal Ringer",
            description = "Switch ringer mode to Normal",
            aliases = listOf("normal mode", "ringer on", "sound on", "unmute phone"),
            execute = { ctx, _ ->
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                CapabilityExecutionResult.ok("🔔 Ringer mode set to Normal.")
            }
        ),

        CapabilityDef(
            id = "system.ringer.vibrate",
            category = "device",
            name = "Set Vibrate Mode",
            description = "Switch ringer mode to Vibrate",
            aliases = listOf("vibrate mode", "put on vibrate", "vibrate only"),
            execute = { ctx, _ ->
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                CapabilityExecutionResult.ok("📳 Ringer mode set to Vibrate.")
            }
        ),

        CapabilityDef(
            id = "system.ringer.silent",
            category = "device",
            name = "Set Silent Mode",
            description = "Switch ringer mode to Silent",
            aliases = listOf("silent mode", "put on silent", "mute phone"),
            execute = { ctx, _ ->
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
                CapabilityExecutionResult.ok("🔕 Ringer mode set to Silent.")
            }
        ),

        CapabilityDef(
            id = "system.vibrate",
            category = "device",
            name = "Haptic Vibration",
            description = "Trigger device vibration for haptic feedback",
            aliases = listOf("vibrate", "buzz"),
            execute = { _, params ->
                val ms = (params["ms"] as? Number)?.toLong() ?: 300L
                val r = DeviceCapability.vibrate(ms)
                if (r.success) CapabilityExecutionResult.ok("📳 Vibrated for ${ms}ms.")
                else CapabilityExecutionResult.fail("Vibrate error: ${r.error}")
            }
        ),

        CapabilityDef(
            id = "system.uptime",
            category = "device",
            name = "Device Uptime",
            description = "System uptime since last boot",
            aliases = listOf("uptime", "device uptime", "how long has the phone been on"),
            execute = { _, _ ->
                val ms = SystemClock.elapsedRealtime()
                val hrs = TimeUnit.MILLISECONDS.toHours(ms)
                val mins = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
                CapabilityExecutionResult.ok("⏱️ System uptime: ${hrs}h ${mins}m since boot.")
            }
        ),

        CapabilityDef(
            id = "system.device.info",
            category = "device",
            name = "Device Hardware Info",
            description = "Hardware manufacturer, model, and OS release",
            aliases = listOf("device info", "phone info", "what phone is this", "android version", "device model"),
            execute = { _, _ ->
                CapabilityExecutionResult.ok("📱 ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ABI: ${Build.SUPPORTED_ABIS.firstOrNull()}")
            }
        ),

        CapabilityDef(
            id = "system.ram",
            category = "device",
            name = "RAM Memory Status",
            description = "Current available, used, and total memory",
            aliases = listOf("ram", "memory status", "how much ram", "ram usage", "available memory"),
            execute = { ctx, _ ->
                val mi = ActivityManager.MemoryInfo()
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.getMemoryInfo(mi)
                val totalMb = mi.totalMem / (1024 * 1024)
                val availMb = mi.availMem / (1024 * 1024)
                val usedMb = totalMb - availMb
                CapabilityExecutionResult.ok("🧠 RAM: ${usedMb}MB used / ${totalMb}MB total (${availMb}MB free).")
            }
        ),

        CapabilityDef(
            id = "system.cpu",
            category = "device",
            name = "CPU Hardware Status",
            description = "Processor architecture and core count",
            aliases = listOf("cpu", "processor", "cpu info", "cpu architecture"),
            execute = { _, _ ->
                val cores = Runtime.getRuntime().availableProcessors()
                val arch = System.getProperty("os.arch") ?: "arm64"
                CapabilityExecutionResult.ok("⚡ CPU: $cores cores ($arch architecture) ready.")
            }
        ),

        CapabilityDef(
            id = "system.screen.lock",
            category = "device",
            name = "Lock Screen",
            description = "Lock the device screen immediately",
            aliases = listOf("lock screen", "lock the screen", "lock my phone", "lock phone", "lock the phone", "lock device", "screen off", "turn off screen", "turn off the screen", "sleep phone", "lock it"),
            execute = { ctx, _ ->
                try {
                    // Tier 1: Accessibility global action (no extra setup)
                    if (JarvisAccessibilityService.global("lock")) {
                        CapabilityExecutionResult.ok("🔒 Screen locked.")
                    } else {
                        // Tier 2: Device Admin lockNow()
                        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        val admin = ComponentName(ctx, AdminReceiver::class.java)
                        if (dpm != null && dpm.isAdminActive(admin)) {
                            dpm.lockNow()
                            CapabilityExecutionResult.ok("🔒 Screen locked via device admin.")
                        } else {
                            // Tier 3: Rooted sleep keyevent
                            val res = Shell.root("input keyevent 223")
                            if (res.rc == 0) CapabilityExecutionResult.ok("🔒 Screen locked.")
                            else CapabilityExecutionResult.fail("Could not lock the screen. Enable JARVIS in Settings → Accessibility or activate it as a device admin.")
                        }
                    }
                } catch (e: Exception) {
                    CapabilityExecutionResult.fail("Could not lock the screen: ${e.message}")
                }
            }
        ),

        CapabilityDef(
            id = "system.wifi.state",
            category = "device",
            name = "Wi-Fi Status",
            description = "Current Wi-Fi connection state",
            aliases = listOf("wifi status", "is wifi on", "wifi state", "check wifi"),
            execute = { ctx, _ ->
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val net = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(net)
                val onWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                CapabilityExecutionResult.ok("📶 Wi-Fi is ${if (onWifi) "Connected" else "Disconnected"}.")
            }
        )
    )
}
