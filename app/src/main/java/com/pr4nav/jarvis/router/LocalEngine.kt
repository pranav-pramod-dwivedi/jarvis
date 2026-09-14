package com.pr4nav.jarvis.router

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.chat.AgentStreamEvent
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Offline last-resort engine. Runs entirely on-device via NeedleRuntime
 * (daemon → CLI → constrained grammar evaluator), so it works with no
 * API keys, no wallet and no network. Dumber than cloud, infinitely
 * better than "all engines failed".
 */
object LocalEngine {

    private const val TAG = "LocalEngine"
    const val TIMEOUT_MS = 20_000L

    private val GREETING = Regex("^(hi+|hey|hello|yo|namaste|ram ram|good\\s?(morning|night|evening)|thanks?|thank you|bye)\\b[!.…]*$", RegexOption.IGNORE_CASE)

    fun isAvailable(): Boolean {
        return try {
            com.pr4nav.jarvis.needle.NeedleRuntime.isRuntimeAvailable ||
                com.pr4nav.jarvis.needle.NeedleRuntime.isModelLoaded
        } catch (_: Exception) {
            false
        }
    }

    fun run(
        context: Context,
        prompt: String,
        t0: Long,
        onEvent: ((AgentStreamEvent) -> Unit)?
    ): UnifiedExecutionResult {
        val latency = { System.currentTimeMillis() - t0 }
        val trimmed = prompt.trim()

        // 1. Offline inference with a hard deadline (skipped when no runtime —
        //    the conversational branch below needs nothing at all).
        val envelope = if (isAvailable()) {
            try {
                CompletableFuture.supplyAsync {
                    com.pr4nav.jarvis.needle.NeedleRuntime.complete(trimmed)
                }.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Local inference failed: ${e.message}")
                null
            }
        } else {
            Log.i(TAG, "No local runtime; conversational offline answer.")
            null
        }

        // 2. Structured tool call → validate + execute + synthesize.
        val call = envelope?.functionCalls?.firstOrNull()
        if (envelope != null && envelope.success && call != null && call.name.isNotBlank()) {
            val toolDef = com.pr4nav.jarvis.tools.CanonicalToolRegistry.get(call.name)
            if (toolDef != null) {
                val argsJson = try {
                    JSONObject(call.arguments.mapValues { it.value?.toString() ?: "" } as Map<*, *>)
                } catch (_: Exception) {
                    JSONObject()
                }
                onEvent?.invoke(AgentStreamEvent.ThinkingDelta("Routing offline to ${call.name}."))
                onEvent?.invoke(AgentStreamEvent.ThinkingDone(latency()))
                onEvent?.invoke(AgentStreamEvent.ToolStart(call.name, "", argsSummary(argsJson)))
                val toolT0 = System.currentTimeMillis()
                val toolRes = try {
                    com.pr4nav.jarvis.tools.CanonicalToolRegistry.execute(context, call.name, argsJson)
                } catch (e: Exception) {
                    com.pr4nav.jarvis.tools.ToolResult(
                        success = false,
                        error = com.pr4nav.jarvis.tools.ToolError(
                            code = "LOCAL_EXEC_FAIL",
                            message = e.message ?: "exec failed"
                        )
                    )
                }
                val toolMs = System.currentTimeMillis() - toolT0
                val outText = toolRes.data?.toString() ?: toolRes.error?.message ?: toolRes.status.name
                onEvent?.invoke(
                    AgentStreamEvent.ToolEnd(
                        tool = call.name, label = "", detail = argsSummary(argsJson),
                        output = outText, exitCode = if (toolRes.success) 0 else 1,
                        durationMs = toolMs, verified = toolRes.success
                    )
                )
                val answer = try {
                    val mode = com.pr4nav.jarvis.response.AnswerSynthesizer
                        .determineResponseMode(trimmed, "DEVICE")
                    com.pr4nav.jarvis.response.AnswerSynthesizer
                        .synthesize(trimmed, call.name, toolRes.data, mode)
                } catch (_: Exception) {
                    if (toolRes.success) "Done." else "That didn't work offline: ${toolRes.error?.message}"
                }
                onEvent?.invoke(AgentStreamEvent.TextDelta(answer))
                onEvent?.invoke(
                    AgentStreamEvent.Final(
                        text = answer, model = "Needle Local (offline)",
                        latencyMs = latency(), handled = toolRes.success,
                        thinkingTrace = "Offline route via ${call.name}.",
                        toolsUsed = 1
                    )
                )
                return UnifiedExecutionResult(
                    handled = toolRes.success,
                    source = ExecutionSource.DETERMINISTIC_NEEDLE,
                    jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse.of(answer),
                    speechResponse = answer,
                    fullSummary = answer,
                    thinkingTrace = "Offline route via ${call.name}.",
                    modelName = "Needle Local (offline)",
                    toolResult = toolRes,
                    latencyMs = latency()
                )
            }
        }

        // 3. Conversational offline answer (honest about limits).
        val lower = trimmed.lowercase()
        val answer = when {
            GREETING.matches(lower) ->
                "Hey. I'm running offline on the local engine — device actions work, cloud thinking doesn't. Try 'turn on flashlight' or 'help'."
            lower == "help" || lower.startsWith("help ") || lower == "what can you do" ->
                "Offline mode: I can run device actions directly (torch, volume, wifi, calls, apps, screenshots), list and read files, and run shell commands. Cloud chat, web answers and UI generation need a network engine."
            else ->
                "I'm offline right now — no cloud engine reachable and no wallet/quota. I can still run device actions directly: tell me what to do on the phone (e.g. 'turn on torch', 'take screenshot', 'list downloads')."
        }
        val think = "Offline conversational answer (no cloud, no structured call)."
        onEvent?.invoke(AgentStreamEvent.ThinkingDelta(think))
        onEvent?.invoke(AgentStreamEvent.ThinkingDone(latency()))
        onEvent?.invoke(AgentStreamEvent.TextDelta(answer))
        onEvent?.invoke(
            AgentStreamEvent.Final(
                text = answer, model = "Needle Local (offline)",
                latencyMs = latency(), handled = true,
                thinkingTrace = think, toolsUsed = 0
            )
        )
        return UnifiedExecutionResult(
            handled = true,
            source = ExecutionSource.DETERMINISTIC_NEEDLE,
            jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse.of(answer),
            speechResponse = answer,
            fullSummary = answer,
            thinkingTrace = think,
            modelName = "Needle Local (offline)",
            latencyMs = latency()
        )
    }

    private fun argsSummary(args: JSONObject): String {
        for (k in listOf("command", "cmd", "path", "file", "query", "tool_name", "title")) {
            val v = args.optString(k, "").trim()
            if (v.isNotBlank()) return if (v.length > 100) v.take(100) + "…" else v
        }
        return ""
    }
}
