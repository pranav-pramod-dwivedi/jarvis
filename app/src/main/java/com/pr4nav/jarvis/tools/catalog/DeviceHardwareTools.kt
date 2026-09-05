package com.pr4nav.jarvis.tools.catalog

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.pr4nav.jarvis.AdminReceiver
import com.pr4nav.jarvis.JarvisAccessibilityService
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.capabilities.AudioCapability
import com.pr4nav.jarvis.capabilities.DeviceCapability
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema
import org.json.JSONObject
import java.util.Locale

object DeviceHardwareTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "device_torch_toggle",
            description = "Toggles flashlight on or off.",
            argumentSchema = schema(prop("state", "boolean", "true for on, false for off")),
            execute = { _, args ->
                val s = if (args.has("state")) args.optBoolean("state") else true
                val r = DeviceCapability.torch(s)
                if (r.success) ok("🔦 Flashlight turned ${if (s) "ON" else "OFF"}.", mapOf("state" to s))
                else CatalogSchemaHelper.fail("TORCH_ERROR", r.error ?: "Torch failed")
            }
        ))

        reg(CanonicalToolDef(
            name = "device_torch_on",
            description = "Turns on the flashlight/torch.",
            argumentSchema = schema(),
            execute = { _, _ ->
                val r = DeviceCapability.torch(true)
                if (r.success) ok("🔦 Flashlight turned ON.") else CatalogSchemaHelper.fail("TORCH_ERROR", r.error ?: "Torch failed")
            }
        ))

        reg(CanonicalToolDef(
            name = "device_torch_off",
            description = "Turns off the flashlight/torch.",
            argumentSchema = schema(),
            execute = { _, _ ->
                val r = DeviceCapability.torch(false)
                if (r.success) ok("🔦 Flashlight turned OFF.") else CatalogSchemaHelper.fail("TORCH_ERROR", r.error ?: "Torch failed")
            }
        ))

        reg(CanonicalToolDef(
            name = "device_volume_set",
            description = "Sets device media volume percentage (0-100).",
            argumentSchema = schema(
                prop("level", "integer", "Volume percentage (0-100)"),
                required = listOf("level")
            ),
            execute = { _, args ->
                val lvl = args.optInt("level", 50)
                val scaled = (lvl * 15 / 100).coerceIn(0, 15)
                AudioCapability.setVolume("music", scaled)
                ok("🔊 Volume set to $lvl%.", mapOf("level" to lvl))
            }
        ))

        reg(CanonicalToolDef(
            name = "device_volume_raise",
            description = "Increases media volume by one step.",
            argumentSchema = schema(),
            execute = { _, _ ->
                AudioCapability.adjustVolume("music", 1)
                ok("🔊 Volume increased.")
            }
        ))

        reg(CanonicalToolDef(
            name = "device_volume_lower",
            description = "Decreases media volume by one step.",
            argumentSchema = schema(),
            execute = { _, _ ->
                AudioCapability.adjustVolume("music", -1)
                ok("🔉 Volume decreased.")
            }
        ))

        reg(CanonicalToolDef(
            name = "device_volume_mute",
            description = "Mutes device media sound.",
            argumentSchema = schema(),
            execute = { _, _ ->
                AudioCapability.setVolume("music", 0)
                ok("🔇 Volume muted.")
            }
        ))

        reg(CanonicalToolDef(
            name = "device_battery_status",
            description = "Checks battery percentage, charging state, and health.",
            argumentSchema = schema(),
            execute = { _, _ ->
                val (pct, charging) = DeviceCapability.battery()
                ok("🔋 Battery: $pct% (${if (charging) "Charging ⚡" else "Discharging"}).", mapOf("level" to pct, "charging" to charging))
            }
        ))

        reg(CanonicalToolDef(
            name = "device_screenshot_take",
            description = "Captures an instant screenshot of the device screen.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                CanonicalToolRegistry.execute(ctx, "take_screenshot", JSONObject())
            }
        ))

        reg(CanonicalToolDef(
            name = "device_vibrate",
            description = "Vibrates the device for a specified duration in milliseconds.",
            argumentSchema = schema(prop("milliseconds", "integer", "Duration in ms (default 300)")),
            execute = { _, args ->
                val ms = args.optLong("milliseconds", 300L)
                DeviceCapability.vibrate(ms)
                ok("📳 Device vibrated.")
            }
        ))

        reg(CanonicalToolDef(
            name = "device_settings_open",
            description = "Opens Android Settings or a specific subpage.",
            argumentSchema = schema(prop("subpage", "string", "Optional subpage: 'wifi', 'bluetooth', 'display', 'sound', 'battery', 'apps'")),
            execute = { ctx, args ->
                val sub = args.optString("subpage", "").lowercase(Locale.ROOT)
                val action = when (sub) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ok("⚙️ Opened Settings ${if (sub.isNotBlank()) "for $sub" else ""}.")
            }
        ))

        reg(CanonicalToolDef(
            name = "device_lock_screen",
            description = "Locks the device screen immediately (lock screen / lock the phone / turn off screen / sleep display).",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                lockScreen(ctx)
            }
        ))
    }

    /**
     * Tiered screen lock: Accessibility global action first (no extra setup),
     * then Device Admin lockNow(), then rooted keyevent sleep.
     */
    private fun lockScreen(ctx: Context): ToolResult {
        // Tier 1: Accessibility GLOBAL_ACTION_LOCK_SCREEN (API 28+)
        try {
            if (JarvisAccessibilityService.global("lock")) {
                return ok("🔒 Screen locked.")
            }
        } catch (_: Exception) {}

        // Tier 2: Device Admin lockNow()
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = ComponentName(ctx, AdminReceiver::class.java)
            if (dpm != null && dpm.isAdminActive(admin)) {
                dpm.lockNow()
                return ok("🔒 Screen locked via device admin.")
            }
        } catch (_: Exception) {}

        // Tier 3: Rooted sleep keyevent
        try {
            val res = Shell.root("input keyevent 223")
            if (res.rc == 0) {
                return ok("🔒 Screen locked.")
            }
        } catch (_: Exception) {}

        return CatalogSchemaHelper.fail(
            "LOCK_UNAVAILABLE",
            "Could not lock the screen. Enable one of: Settings → Accessibility → JARVIS, or activate JARVIS as a device admin."
        )
    }
}
