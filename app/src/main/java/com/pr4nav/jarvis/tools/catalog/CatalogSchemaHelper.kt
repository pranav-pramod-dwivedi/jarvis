package com.pr4nav.jarvis.tools.catalog

import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

object CatalogSchemaHelper {

    fun schema(vararg properties: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val propsObj = JSONObject()
        for ((name, p) in properties) {
            propsObj.put(name, p)
        }
        val obj = JSONObject().apply {
            put("type", "object")
            put("properties", propsObj)
        }
        if (required.isNotEmpty()) {
            obj.put("required", JSONArray(required))
        }
        return obj
    }

    fun prop(name: String, type: String, description: String, enumVals: List<String>? = null): Pair<String, JSONObject> {
        val obj = JSONObject().apply {
            put("type", type)
            put("description", description)
            if (!enumVals.isNullOrEmpty()) {
                put("enum", JSONArray(enumVals))
            }
        }
        return name to obj
    }

    fun ok(message: String, extra: Map<String, Any> = emptyMap()): ToolResult {
        val json = JSONObject().put("message", message)
        for ((k, v) in extra) {
            json.put(k, v)
        }
        return ToolResult.ok(json)
    }

    fun fail(code: String, message: String): ToolResult {
        return ToolResult.failure(code, message)
    }

    fun safeStartActivity(context: android.content.Context, intent: android.content.Intent): Boolean {
        return try {
            if (intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w("CatalogSchemaHelper", "Activity not found: ${e.message}")
            false
        } catch (e: Exception) {
            android.util.Log.w("CatalogSchemaHelper", "safeStartActivity failed: ${e.message}")
            false
        }
    }
}
