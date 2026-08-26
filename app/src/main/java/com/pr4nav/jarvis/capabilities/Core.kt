package com.pr4nav.jarvis.capabilities

import android.content.Context
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject

data class CapabilityResult(
    val success: Boolean,
    val data: String? = null,
    val error: String? = null,
    val meta: Map<String, String?> = emptyMap()
) {
    companion object {
        fun ok(data: String? = null, vararg meta: Pair<String, String?>) =
            CapabilityResult(true, data, null, meta.toMap())
        fun fail(error: String, vararg meta: Pair<String, String?>) =
            CapabilityResult(false, null, error, meta.toMap())
    }

    fun envelope(): JSONObject = JSONObject().apply {
        put("ok", success)
        if (error != null) put("error", error)
        if (data != null) put("data", dataAsNode())
        for ((k, v) in meta) if (v != null) put(k, v)
    }

    private fun dataAsNode(): Any = try {
        JSONObject(data!!)
    } catch (_: Exception) {
        try { org.json.JSONArray(data!!) } catch (_: Exception) { data!! }
    }
}

interface Capability {
    val name: String
    fun available(): Boolean
    fun permitted(): Boolean
    fun status(): String
    fun tools(): List<ToolDef> = emptyList()
}

object Capabilities {
    @Volatile var app: Context? = null
        private set

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    fun require(): Context =
        app ?: throw IllegalStateException("Capabilities.init not called yet")

    fun all(): List<Capability> = listOf(
        FileCapability, RootCapability, AppCapability, BrowserCapability,
        ClipboardCapability, ScreenshotCapability, AudioCapability,
        LocationCapability, NotificationCapability, DeviceCapability,
        AccessibilityCapability, TermuxCapability, OpenCodeCapability
    )

    fun statuses(): String = all().joinToString("\n") { it.status() }
}
