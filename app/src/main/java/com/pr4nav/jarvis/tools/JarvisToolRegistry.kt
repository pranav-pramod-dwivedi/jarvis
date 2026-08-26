package com.pr4nav.jarvis.tools

import com.pr4nav.jarvis.capabilities.Capabilities
import org.json.JSONObject

class ToolDef(
    val name: String,
    val description: String,
    val argsHint: String = "{}",
    val gate: (() -> String?)? = null,
    val run: (JSONObject) -> JSONObject
)

object JarvisToolRegistry {

    private val tools = LinkedHashMap<String, ToolDef>()
    @Volatile private var registered = false

    @Synchronized
    fun registerAll(ctx: android.content.Context) {
        Capabilities.init(ctx)
        if (registered) return
        for (cap in Capabilities.all()) register(cap.tools())
        registered = true
    }

    @Synchronized
    fun register(defs: List<ToolDef>) {
        for (d in defs) tools[d.name] = d
    }

    fun names(): List<String> = tools.keys.toList()

    fun get(name: String): ToolDef? = tools[name]

    fun catalog(): String =
        if (tools.isEmpty()) "no tools registered"
        else tools.values.joinToString("\n") { "- ${it.name}: ${it.description} args=${it.argsHint}" }

    fun execute(name: String, argsJson: String?): JSONObject {
        val def = tools[name] ?: return err("unknown tool: $name. available=${names()}")
        try {
            def.gate?.let { g ->
                g()?.let { reason -> return err(reason) }
            }
            val args = if (argsJson.isNullOrBlank()) JSONObject() else JSONObject(argsJson)
            return def.run(args)
        } catch (e: Exception) {
            return err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun err(message: String): JSONObject {
        val o = JSONObject()
        o.put("ok", false)
        o.put("error", message)
        return o
    }
}
