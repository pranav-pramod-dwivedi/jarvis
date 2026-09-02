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
}
