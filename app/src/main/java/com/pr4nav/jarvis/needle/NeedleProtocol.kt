package com.pr4nav.jarvis.needle

import org.json.JSONArray
import org.json.JSONObject

/**
 * Route decision types produced by Needle 2 local router.
 */
enum class RouteType {
    DIRECT_TOOL,   // High-confidence deterministic capability -> execute immediately
    GUI,           // Capability requires visual inspection -> JARVIS GUI renderer
    ESCALATE,      // Complex/unsupported request -> escalate to conversational LLM / AGY / OpenCode
    CLARIFICATION  // Ambiguous parameters or medium confidence -> ask user
}

/**
 * Fine-grained timing instrumentation for every stage of the reflex pipeline.
 */
data class TimingMetrics(
    val inputReceivedMs: Long = System.currentTimeMillis(),
    var needleStartMs: Long = 0L,
    var needleEndMs: Long = 0L,
    var toolStartMs: Long = 0L,
    var toolEndMs: Long = 0L,
    var ttsStartMs: Long = 0L
) {
    val needleLatencyMs: Long get() = if (needleEndMs > needleStartMs) needleEndMs - needleStartMs else 0L
    val toolLatencyMs: Long get() = if (toolEndMs > toolStartMs) toolEndMs - toolStartMs else 0L
    val totalLatencyMs: Long get() = (toolEndMs.takeIf { it > 0 } ?: needleEndMs) - inputReceivedMs
}

/**
 * Structured tool call specification inside a Needle 2 envelope.
 */
data class FunctionCall(
    val name: String,
    val arguments: Map<String, Any?>
)

/**
 * Representation of the official upstream Needle 2 JSON output.
 */
data class NeedleEnvelope(
    val type: String,
    val success: Boolean,
    val functionCalls: List<FunctionCall>,
    val confidence: Double,
    val reasoning: String?,
    val prefillTps: Double?,
    val decodeTps: Double?,
    val peakRamMb: Double?,
    val rawJson: JSONObject
) {
    companion object {
        fun fromJson(jsonStr: String): NeedleEnvelope {
            val obj = JSONObject(jsonStr)
            val calls = mutableListOf<FunctionCall>()
            val arr = obj.optJSONArray("function_calls") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.getString("name")
                val argsObj = item.optJSONObject("arguments") ?: JSONObject()
                val argsMap = mutableMapOf<String, Any?>()
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    argsMap[k] = argsObj.opt(k)
                }
                calls.add(FunctionCall(name, argsMap))
            }
            return NeedleEnvelope(
                type = obj.optString("type", "call"),
                success = obj.optBoolean("success", true),
                functionCalls = calls,
                confidence = obj.optDouble("confidence", 0.0),
                reasoning = if (obj.has("reasoning") && !obj.isNull("reasoning")) obj.getString("reasoning") else null,
                prefillTps = if (obj.has("prefill_tps")) obj.optDouble("prefill_tps") else null,
                decodeTps = if (obj.has("decode_tps")) obj.optDouble("decode_tps") else null,
                peakRamMb = if (obj.has("peak_ram_mb")) obj.optDouble("peak_ram_mb") else null,
                rawJson = obj
            )
        }
    }
}

/**
 * Result returned by NeedleRouter.route(input, context).
 */
data class NeedleRouteResult(
    val route: RouteType,
    val tool: String?,
    val arguments: Map<String, Any?>,
    val confidence: Double,
    val reasoning: String?,
    var executionSummary: String? = null,
    val timing: TimingMetrics = TimingMetrics(),
    val envelope: NeedleEnvelope? = null
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("route", route.name)
        if (tool != null) o.put("tool", tool)
        o.put("arguments", JSONObject(arguments))
        o.put("confidence", confidence)
        if (reasoning != null) o.put("reasoning", reasoning)
        if (executionSummary != null) o.put("executionSummary", executionSummary)
        val t = JSONObject()
        t.put("needleLatencyMs", timing.needleLatencyMs)
        t.put("toolLatencyMs", timing.toolLatencyMs)
        t.put("totalLatencyMs", timing.totalLatencyMs)
        o.put("timing", t)
        return o
    }
}
