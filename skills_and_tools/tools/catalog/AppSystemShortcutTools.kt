package com.pr4nav.jarvis.tools.catalog

import android.content.Intent
import android.os.Environment
import android.os.StatFs
import com.pr4nav.jarvis.JarvisAccessibilityService
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.capabilities.AccessibilityCapability
import com.pr4nav.jarvis.response.AnswerSynthesizer
import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object AppSystemShortcutTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "app_launch_friendly",
            description = "Opens an installed Android app by its human friendly name.",
            argumentSchema = schema(
                prop("app", "string", "Friendly name of app (e.g. 'YouTube', 'WhatsApp')"),
                required = listOf("app")
            ),
            execute = { ctx, args ->
                val name = args.optString("app", "")
                val friendly = AnswerSynthesizer.cleanFriendlyAppName(name)
                JarvisIntentRouter.routeAndExecute(ctx, "Open $friendly") {}
                ok("▶️ Opening $friendly.", mapOf("app" to friendly))
            }
        ))

        reg(CanonicalToolDef(
            name = "file_storage_stats",
            description = "Returns available and total storage space on device.",
            argumentSchema = schema(),
            execute = { _, _ ->
                val f = Environment.getDataDirectory()
                val stat = StatFs(f.path)
                val availBytes = stat.availableBytes
                val totalBytes = stat.totalBytes
                val availGb = availBytes / (1024.0 * 1024.0 * 1024.0)
                val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
                val msg = "💾 Storage: %.1f GB free of %.1f GB total.".format(availGb, totalGb)
                ok(msg, mapOf("freeGb" to availGb, "totalGb" to totalGb))
            }
        ))

        // High-speed text-based screen capture (eliminates screenshot latency & token overhead)
        val readScreenDef = CanonicalToolDef(
            name = "read_screen_text",
            description = "High-speed text-only screen capture that reads all text and coordinates on the current display. Saves to /storage/emulated/0/JARVIS/workspace/screen_capture.txt for immediate agent reading and virtual tap targeting.",
            argumentSchema = schema(),
            execute = { _, _ ->
                val txt = JarvisAccessibilityService.screenTextWithCoordinates()
                ok("📱 Text Screen Capture (+ coordinates saved to /storage/emulated/0/JARVIS/workspace/screen_capture.txt):\n$txt",
                    mapOf("screenText" to txt, "txtPath" to "/storage/emulated/0/JARVIS/workspace/screen_capture.txt"))
            }
        )
        reg(readScreenDef)
        reg(readScreenDef.copy(name = "screencapture"))
        reg(readScreenDef.copy(name = "screen_read"))

        // Virtual touches & gestures (tapping coordinates or clicking by text)
        val virtualTouchDef = CanonicalToolDef(
            name = "virtual_touch",
            description = "Dispatches a virtual touch tap on the screen at coordinates (x, y) or clicks an element by visible text label. Automatically uses root fallback if accessibility is unavailable.",
            argumentSchema = schema(
                prop("text", "string", "Optional visible text or button description to click"),
                prop("x", "integer", "Optional X coordinate on screen"),
                prop("y", "integer", "Optional Y coordinate on screen")
            ),
            execute = { _, args ->
                val text = args.optString("text").trim()
                val x = args.optInt("x", -1)
                val y = args.optInt("y", -1)

                if (text.isNotEmpty()) {
                    var clicked = AccessibilityCapability.clickByText(text).success
                    if (!clicked) {
                        // Fallback: search bounds in screenTextWithCoordinates and tap center via root
                        val dump = JarvisAccessibilityService.screenTextWithCoordinates()
                        val line = dump.lines().firstOrNull { it.contains(text, ignoreCase = true) }
                        if (line != null) {
                            val match = Regex("""center=\((\d+),\s*(\d+)\)""").find(line)
                            if (match != null) {
                                val (cx, cy) = match.destructured
                                val tapRes = Shell.root("input tap $cx $cy")
                                clicked = tapRes.rc == 0
                            }
                        }
                    }
                    if (clicked) ok("👆 Virtual touch clicked: \"$text\".", mapOf("target" to text))
                    else CatalogSchemaHelper.fail("CLICK_FAILED", "Element with text '$text' not found or could not be clicked")
                } else if (x >= 0 && y >= 0) {
                    val tapped = JarvisAccessibilityService.gestureTap(x.toFloat(), y.toFloat())
                    if (tapped) ok("👆 Virtual touch tapped at ($x, $y).", mapOf("x" to x, "y" to y))
                    else CatalogSchemaHelper.fail("TAP_FAILED", "Failed to tap coordinates ($x, $y)")
                } else {
                    CatalogSchemaHelper.fail("INVALID_ARGS", "Must provide 'text' label or ('x', 'y') coordinates")
                }
            }
        )
        reg(virtualTouchDef)
        reg(virtualTouchDef.copy(name = "virtualtouches"))
        reg(virtualTouchDef.copy(name = "tap"))

        // Virtual scroll gesture
        reg(CanonicalToolDef(
            name = "virtual_scroll",
            description = "Dispatches a virtual scroll gesture on the active window.",
            argumentSchema = schema(
                prop("direction", "string", "Scroll direction: 'up', 'down', 'forward', 'backward'", listOf("up", "down", "forward", "backward"))
            ),
            execute = { _, args ->
                if (!AccessibilityCapability.enabled()) {
                    return@CanonicalToolDef CatalogSchemaHelper.fail("ACCESSIBILITY_DISABLED", "Accessibility Service is not enabled. Please enable 'Jarvis' in Android Settings -> Accessibility.")
                }
                val dir = args.optString("direction", "down").lowercase()
                val forward = dir == "down" || dir == "forward"
                val r = AccessibilityCapability.scroll(forward)
                if (r.success) ok("📜 Scrolled $dir.")
                else CatalogSchemaHelper.fail("SCROLL_FAILED", "No scrollable container found on current screen")
            }
        ))

        // Virtual text typing
        reg(CanonicalToolDef(
            name = "virtual_type",
            description = "Types text into the currently focused input field.",
            argumentSchema = schema(
                prop("text", "string", "Text string to input"),
                required = listOf("text")
            ),
            execute = { _, args ->
                if (!AccessibilityCapability.enabled()) {
                    return@CanonicalToolDef CatalogSchemaHelper.fail("ACCESSIBILITY_DISABLED", "Accessibility Service is not enabled. Please enable 'Jarvis' in Android Settings -> Accessibility.")
                }
                val text = args.optString("text")
                val r = AccessibilityCapability.type(text)
                if (r.success) ok("⌨️ Typed text: \"$text\".")
                else CatalogSchemaHelper.fail("TYPE_FAILED", "No focused editable text field found")
            }
        ))

        // Virtual global system keys
        reg(CanonicalToolDef(
            name = "press_global_key",
            description = "Presses a system navigation key (back, home, recents, notifications, quick_settings, lock_screen).",
            argumentSchema = schema(
                prop("key", "string", "Key action ('back', 'home', 'recents', 'notifications', 'quick_settings', 'lock_screen')"),
                required = listOf("key")
            ),
            execute = { _, args ->
                if (!AccessibilityCapability.enabled()) {
                    return@CanonicalToolDef CatalogSchemaHelper.fail("ACCESSIBILITY_DISABLED", "Accessibility Service is not enabled. Please enable 'Jarvis' in Android Settings -> Accessibility.")
                }
                val key = args.optString("key", "back").lowercase()
                val r = AccessibilityCapability.global(key)
                if (r.success) ok("🔘 Pressed system key: $key.")
                else CatalogSchemaHelper.fail("KEY_FAILED", "Failed to dispatch system key: $key")
            }
        ))

        reg(CanonicalToolDef(
            name = "diagnostic_ping",
            description = "Pings a network host to test connectivity.",
            argumentSchema = schema(prop("host", "string", "Host to ping (default 8.8.8.8)")),
            execute = { _, args ->
                val host = args.optString("host", "8.8.8.8")
                val r = Shell.termux("ping -c 2 -W 2 $host", 5000)
                val msg = if (r.rc == 0) "🌐 Connectivity verified: $host is reachable." else "❌ Host $host unreachable."
                ok(msg, mapOf("output" to r.out))
            }
        ))
    }
}
