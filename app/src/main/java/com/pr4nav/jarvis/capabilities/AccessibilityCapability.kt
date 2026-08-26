package com.pr4nav.jarvis.capabilities

import android.provider.Settings
import com.pr4nav.jarvis.JarvisAccessibilityService
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONArray
import org.json.JSONObject

object AccessibilityCapability : Capability {

    override val name = "accessibility"

    fun enabled(): Boolean {
        val enabled = Settings.Secure.getString(
            Capabilities.require().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val svc = JarvisAccessibilityService::class.java.name
        return enabled.split(':').any {
            it.equals("${Capabilities.require().packageName}/$svc", true) ||
                    it.equals("${Capabilities.require().packageName}.JarvisAccessibilityService", true)
        }
    }

    private fun bound() = JarvisAccessibilityService.instance

    private fun gate(): String? = when {
        Capabilities.app == null -> "not initialized"
        !enabled() -> "Accessibility access is required — enable 'Jarvis' in Settings → Accessibility"
        bound() == null -> "Accessibility granted but service is not connected — toggle it off/on or reopen JARVIS"
        else -> null
    }

    fun inspect(): CapabilityResult {
        val nodes = JarvisAccessibilityService.walk(150)
        if (nodes.isEmpty()) return CapabilityResult.fail("No active window content available")
        val arr = JSONArray()
        for (n in nodes.take(120)) {
            arr.put(JSONObject()
                .put("path", JSONArray(n.path))
                .put("text", n.text).put("desc", n.desc)
                .put("class", n.cls.substringAfterLast('.'))
                .put("id", n.viewId)
                .put("clickable", n.clickable).put("scrollable", n.scrollable)
                .put("editable", n.editable)
                .put("checked", if (n.checked == null) JSONObject.NULL else n.checked)
                .put("bounds", n.bounds))
        }
        return CapabilityResult.ok(arr.toString(), "count" to arr.length().toString())
    }

    fun readScreen(): CapabilityResult {
        val text = JarvisAccessibilityService.screenText()
        return if (text.isBlank()) CapabilityResult.fail("No readable content on the active window")
        else CapabilityResult.ok(JSONObject().put("screen", text.take(12_000)).toString())
    }

    fun clickByText(text: String): CapabilityResult =
        wrap(JarvisAccessibilityService.clickByText(text), "click", text)

    fun clickAt(path: List<Int>): CapabilityResult =
        wrap(JarvisAccessibilityService.clickAt(path), "click", path.toString())

    fun longClickAt(path: List<Int>): CapabilityResult =
        wrap(JarvisAccessibilityService.longClick(path), "longclick", path.toString())

    fun scroll(forward: Boolean): CapabilityResult =
        wrap(JarvisAccessibilityService.scroll(forward), "scroll", if (forward) "forward" else "backward")

    fun type(text: String): CapabilityResult =
        wrap(JarvisAccessibilityService.typeText(text), "type", text)

    fun global(action: String): CapabilityResult =
        wrap(JarvisAccessibilityService.global(action), "global", action)

    fun tap(x: Int, y: Int): CapabilityResult =
        wrap(JarvisAccessibilityService.gestureTap(x.toFloat(), y.toFloat()), "tap", "$x,$y")

    private fun wrap(ok: Boolean, op: String, arg: String): CapabilityResult =
        if (ok) CapabilityResult.ok(JSONObject().put(op, arg).toString())
        else CapabilityResult.fail("$op failed for '$arg' — element may not support this action")

    override fun available(): Boolean = true
    override fun permitted(): Boolean = try { enabled() } catch (_: Exception) { false }
    override fun status(): String = when {
        !permitted() -> "✗ Accessibility — not enabled in system settings"
        bound() == null -> "△ Accessibility — enabled, service reconnecting"
        else -> "✓ Accessibility — UI automation ready"
    }

    override fun tools() = listOf(
        ToolDef("accessibility.inspect", "List visible interactive UI elements with paths", "{}",
            ::gate, { _ -> inspect().envelope() }),
        ToolDef("accessibility.read", "Read visible text of the current screen", "{}",
            ::gate, { _ -> readScreen().envelope() }),
        ToolDef("accessibility.clickText", "Click an element by its visible text/description", """{"text":"OK"}""",
            ::gate, { a -> clickByText(a.getString("text")).envelope() }),
        ToolDef("accessibility.clickPath", "Click element at an inspect path", """{"path":[0,2,1]}""",
            ::gate,
            { a ->
                val p = a.optJSONArray("path")?.let { j -> (0 until j.length()).map { j.getInt(it) } }
                    ?: return@ToolDef JSONObject().put("ok", false).put("error", "path array required")
                clickAt(p).envelope()
            }),
        ToolDef("accessibility.longClick", "Long-click element at an inspect path", """{"path":[0,2]}""",
            ::gate,
            { a ->
                val p = a.optJSONArray("path")?.let { j -> (0 until j.length()).map { j.getInt(it) } }
                    ?: return@ToolDef JSONObject().put("ok", false).put("error", "path array required")
                longClickAt(p).envelope()
            }),
        ToolDef("accessibility.scroll", "Scroll forward/backward on screen", """{"forward":true}""",
            ::gate, { a -> scroll(a.optBoolean("forward", true)).envelope() }),
        ToolDef("accessibility.type", "Type text into the focused input field", """{"text":"hello"}""",
            ::gate, { a -> type(a.getString("text")).envelope() }),
        ToolDef("accessibility.global", "Global action: back/home/recents/notifications/lock", """{"action":"back"}""",
            ::gate, { a -> global(a.getString("action")).envelope() }),
        ToolDef("accessibility.tap", "Coordinate tap fallback (use only when semantic actions cannot reach the element)", """{"x":540,"y":960}""",
            ::gate, { a -> tap(a.optInt("x", 0), a.optInt("y", 0)).envelope() })
    )
}
