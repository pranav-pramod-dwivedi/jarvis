package com.pr4nav.jarvis.router

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.chat.AgentStreamEvent
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.llm.GroqClient
import com.pr4nav.jarvis.llm.KiraClient
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONObject

enum class AgentExecutionMode(val displayName: String, val badge: String, val description: String) {
    KIRA_PRIMARY("Kira AI (Default)", "[Kira Primary]", "Kira Free Cascade (GLM 5.3 -> MiMo 2.5 -> Qwen 3.8) + Groq Backup"),
    GROQ_PRIMARY("Groq Compound", "[Groq Primary]", "Groq LLaMA 3.3 70B Compound Agent with Kira Backup"),
    AUTO("Auto (Kira + Groq + Needle)", "[Auto Dual-Engine]", "Cascades Needle Reflex -> Kira Free Cascade -> Groq Compound"),
    NEEDLE_ONLY("Needle Only", "[Needle Only]", "Fast Deterministic On-Device Actions"),
    // Backwards-compatible aliases
    CLOUD_NEEDLE("Kira + Needle", "[Kira + Needle]", "Kira AI Free Cascade + Needle Reflex"),
    GROQ_NEEDLE("Groq + Needle", "[Groq + Needle]", "Groq Compound Agent + Needle Reflex")
}

enum class ExecutionSource(val label: String, val badge: String) {
    DETERMINISTIC_NEEDLE("Needle 2 Reflex", "[Needle 2 Reflex]"),
    KIRA_AGENT("Kira AI Agent", "[Kira Agent]"),
    GROQ_AGENT("Groq Compound Agent", "[Groq Agent]"),
    AGY_AGENT("AGY Autonomous Agent", "[AGY Agent]"),
    CLOUD_LLM("Gemini 2.0 Flash (Cloud)", "[Gemini 2.0 Flash (Cloud)]"),
    FALLBACK("System Fallback", "[System Fallback]")
}

/** Engines that can appear in the user-configurable fallback route. */
enum class RouteEngine(val id: String, val short: String) {
    KIRA("kira", "K"),
    GROQ("groq", "G"),
    GEMINI("gemini", "Gm")
}

data class UnifiedExecutionResult(
    val handled: Boolean,
    val source: ExecutionSource,
    val jarvisResponse: com.pr4nav.jarvis.response.JarvisResponse,
    val speechResponse: String = jarvisResponse.speechText,
    val fullSummary: String = jarvisResponse.text,
    val thinkingTrace: String = "",
    val modelName: String = source.label,
    val toolResult: ToolResult? = null,
    val latencyMs: Long = 0L
) {
    // Secondary constructor for backwards compatibility
    constructor(
        handled: Boolean,
        source: ExecutionSource,
        speechResponse: String,
        fullSummary: String = speechResponse,
        thinkingTrace: String = "",
        modelName: String = source.label,
        toolResult: ToolResult? = null,
        latencyMs: Long = 0L
    ) : this(
        handled = handled,
        source = source,
        jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse.of(speechResponse),
        speechResponse = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitizeForSpeech(speechResponse),
        fullSummary = fullSummary,
        thinkingTrace = thinkingTrace,
        modelName = modelName,
        toolResult = toolResult,
        latencyMs = latencyMs
    )
}

/**
 * Unified Autonomous Assistant Dispatcher.
 * Configurable via AgentExecutionMode:
 * 1. KIRA_PRIMARY (Default): Needle Reflex -> Kira AI Free Cascade (glm-5.3-free -> mimo-v2.5-free -> qwen3.8-flash-free) -> Groq
 * 2. GROQ_PRIMARY: Needle Reflex -> Groq Compound Agent -> Kira AI
 * 3. NEEDLE_ONLY: Needle Reflex exclusively (Fast on-device execution)
 */
object UnifiedAssistantDispatcher {

    private const val TAG = "UnifiedAssistant"
    private const val PREFS_NAME = "jarvis_mode_prefs"
    private const val KEY_SELECTED_MODE = "jarvis_agent_mode"
    private const val KEY_ROUTE = "fallback_route" // e.g. "kira,groq,gemini"

    /**
     * Synthesizes structured chat events for non-streaming paths so the UI renders
     * thinking blocks, tool cards and message bubbles uniformly on every engine.
     * (Streaming engines emit these themselves; never double-emit for Kira.)
     */
    private fun emitTurn(
        onEvent: ((AgentStreamEvent) -> Unit)?,
        thinking: String?,
        answer: String,
        model: String,
        latencyMs: Long,
        handled: Boolean,
        toolName: String? = null,
        toolDetail: String = "",
        toolOutput: String = "",
        toolExit: Int = 0,
        toolMs: Long = 0L
    ) {
        if (onEvent == null) return
        val cleanThink = thinking
            ?.replace(Regex("(?i)</?(think|thought|reasoning)>"), "")
            ?.trim().orEmpty()
        if (cleanThink.isNotBlank()) onEvent(AgentStreamEvent.ThinkingDelta(cleanThink))
        onEvent(AgentStreamEvent.ThinkingDone(latencyMs))
        if (toolName != null) {
            onEvent(AgentStreamEvent.ToolStart(toolName, "", toolDetail))
            onEvent(
                AgentStreamEvent.ToolEnd(
                    tool = toolName, label = "", detail = toolDetail,
                    output = toolOutput, exitCode = toolExit,
                    durationMs = toolMs, verified = toolExit == 0
                )
            )
        }
        val finalText = if (answer.isNotBlank()) answer else "Done."
        onEvent(AgentStreamEvent.TextDelta(finalText))
        onEvent(
            AgentStreamEvent.Final(
                text = finalText, model = model, latencyMs = latencyMs,
                handled = handled, thinkingTrace = cleanThink,
                toolsUsed = if (toolName != null) 1 else 0
            )
        )
    }

    private fun argSummary(args: JSONObject): String {
        val keys = listOf("command", "cmd", "path", "file", "query", "tool_name", "title", "pattern")
        for (k in keys) {
            val v = args.optString(k, "").trim()
            if (v.isNotBlank()) return if (v.length > 120) v.take(120) + "…" else v
        }
        return ""
    }

    @Volatile private var inMemoryMode: AgentExecutionMode = AgentExecutionMode.KIRA_PRIMARY

    fun getAgentMode(context: Context?): AgentExecutionMode {
        if (context == null) return inMemoryMode
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs?.getString(KEY_SELECTED_MODE, inMemoryMode.name)
            if (name != null) {
                try {
                    AgentExecutionMode.valueOf(name)
                } catch (_: Exception) {
                    AgentExecutionMode.KIRA_PRIMARY
                }
            } else inMemoryMode
        } catch (_: Exception) {
            inMemoryMode
        }
    }

    fun setAgentMode(context: Context?, mode: AgentExecutionMode) {
        inMemoryMode = mode
        if (context == null) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs?.edit()?.putString(KEY_SELECTED_MODE, mode.name)?.apply()
        } catch (_: Exception) {}
    }

    // ── Fallback route (user-configurable engine chain) ───────────────────────

    val ROUTE_PRESETS: List<Pair<String, List<RouteEngine>>> = listOf(
        "Auto · Kira → Groq → Gemini" to listOf(RouteEngine.KIRA, RouteEngine.GROQ, RouteEngine.GEMINI),
        "Kira only (solo engine)" to listOf(RouteEngine.KIRA),
        "Groq only (solo engine)" to listOf(RouteEngine.GROQ),
        "Gemini only (solo engine)" to listOf(RouteEngine.GEMINI),
        "Kira → Gemini (skip Groq)" to listOf(RouteEngine.KIRA, RouteEngine.GEMINI),
        "Groq → Gemini (skip Kira)" to listOf(RouteEngine.GROQ, RouteEngine.GEMINI),
        "Groq → Kira → Gemini" to listOf(RouteEngine.GROQ, RouteEngine.KIRA, RouteEngine.GEMINI)
    )

    fun getRoute(context: Context?): List<RouteEngine> {
        val fallback = listOf(RouteEngine.KIRA, RouteEngine.GROQ, RouteEngine.GEMINI)
        if (context == null) return fallback
        return try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_ROUTE, null)?.trim().orEmpty()
            if (raw.isBlank()) return fallback
            val parsed = raw.split(',').mapNotNull { id ->
                RouteEngine.values().firstOrNull { it.id == id.trim().lowercase() }
            }.distinct()
            if (parsed.isEmpty()) fallback else parsed
        } catch (_: Exception) {
            fallback
        }
    }

    fun setRoute(context: Context?, route: List<RouteEngine>) {
        if (context == null || route.isEmpty()) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_ROUTE, route.joinToString(",") { it.id })?.apply()
        } catch (_: Exception) {}
    }

    fun routeShort(route: List<RouteEngine>): String {
        if (route.size == 1) return "${route[0].name.lowercase().replaceFirstChar { it.uppercase() }} solo"
        return route.joinToString("→") { it.short }
    }

    private fun nextAfter(route: List<RouteEngine>, current: RouteEngine): RouteEngine? {
        val i = route.indexOf(current)
        if (i == -1) return route.firstOrNull()
        return route.getOrNull(i + 1)
    }

    private fun emitRouteExhausted(
        onEvent: ((AgentStreamEvent) -> Unit)?,
        onResult: (UnifiedExecutionResult) -> Unit,
        lastErr: String,
        t0: Long
    ) {
        val latency = System.currentTimeMillis() - t0
        val msg = "All engines in the fallback route failed. Last error: $lastErr"
        emitTurn(onEvent, "Route exhausted: $lastErr", msg, "None (Unavailable)", latency, false)
        onResult(
            UnifiedExecutionResult(
                handled = false,
                source = ExecutionSource.FALLBACK,
                jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse.of(msg),
                speechResponse = msg,
                fullSummary = msg,
                thinkingTrace = "Route exhausted: $lastErr",
                modelName = "None (Unavailable)",
                latencyMs = latency
            )
        )
    }

    fun execute(
        context: Context,
        rawQuery: String,
        onStatus: ((String) -> Unit)? = null,
        onChunk: ((String) -> Unit)? = null,
        onEvent: ((AgentStreamEvent) -> Unit)? = null,
        onResult: (UnifiedExecutionResult) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        val resolvedQuery = com.pr4nav.jarvis.context.ConversationalContext.resolvePronouns(rawQuery)
        val trimmed = resolvedQuery.trim()

        if (trimmed.isEmpty()) {
            emitTurn(onEvent, "Empty query received; prompting user for instructions.",
                "Yes? How can I help you?", "JARVIS Core", System.currentTimeMillis() - t0, false);
            onResult(
                UnifiedExecutionResult(
                    handled = false,
                    source = ExecutionSource.FALLBACK,
                    speechResponse = "Yes? How can I help you?",
                    fullSummary = "⚙️ [Local System]\nYes? How can I help you?",
                    thinkingTrace = "Empty query received; prompting user for instructions.",
                    latencyMs = System.currentTimeMillis() - t0
                )
            )
            return
        }

        val mode = getAgentMode(context)
        CanonicalToolRegistry.init(context)

        // 0. Safety Guard & Context Disambiguation / Continuation Pre-Routing
        val preDecision = PreRoutingPipeline.filter(context, trimmed)
        if (preDecision is PreRoutingDecision.Blocked) {
            val latency = System.currentTimeMillis() - t0
            val blockMsg = "⚠️ Command Blocked: ${preDecision.reason}"
            if (onEvent == null) onChunk?.invoke(blockMsg)
            emitTurn(onEvent, "Safety guard intercepted command: ${preDecision.reason}",
                blockMsg, "Safety Policy", latency, false);
            onResult(
                UnifiedExecutionResult(
                    handled = false,
                    source = ExecutionSource.FALLBACK,
                    speechResponse = blockMsg,
                    fullSummary = "⚠️ [Safety Policy]\n${preDecision.reason}",
                    thinkingTrace = "Safety guard intercepted command: ${preDecision.reason}",
                    latencyMs = latency
                )
            )
            return
        }

        if (preDecision is PreRoutingDecision.DirectToolMatch) {
            val tool = preDecision.toolName
            val args = preDecision.arguments
            val validation = com.pr4nav.jarvis.tools.ToolValidator.validate(context, tool, args, trimmed)
            if (validation is com.pr4nav.jarvis.tools.ValidationResult.Valid) {
                try {
                    onStatus?.invoke("⚡ Executing [$tool] via Pre-Routing Match...")
                    val toolRes = CanonicalToolRegistry.execute(context, tool, args)
                    if (toolRes.success) {
                        com.pr4nav.jarvis.context.ContextManager.updateToolContext(tool, args)
                    }
                    val toolDef = CanonicalToolRegistry.get(tool)
                    val responseMode = com.pr4nav.jarvis.response.AnswerSynthesizer.determineResponseMode(trimmed, "DEVICE")
                    val synthesizedAnswer = com.pr4nav.jarvis.response.AnswerSynthesizer.synthesize(trimmed, tool, toolRes.data as? JSONObject, responseMode)
                    val latency = System.currentTimeMillis() - t0
                    val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Router: Pre-Routing Continuation Match [$tool]\n• Reason: ${preDecision.reason}\n• Latency: ${latency}ms\n</think>"
                    if (onEvent == null) onChunk?.invoke(synthesizedAnswer)
                                                emitTurn(onEvent, thinkTrace, synthesizedAnswer, "Needle 2 Reflex", latency, true,
                        tool, argSummary(args),
                        (toolRes.data?.toString() ?: toolRes.error?.message ?: "").take(1200),
                        if (toolRes.success) 0 else 1, latency);
                    onResult(
                        UnifiedExecutionResult(
                            handled = true,
                            source = ExecutionSource.DETERMINISTIC_NEEDLE,
                            speechResponse = synthesizedAnswer,
fullSummary = "$thinkTrace\n\n⚡ [Pre-Routing Match · ${latency}ms]\n$synthesizedAnswer",
                            thinkingTrace = thinkTrace,
                            modelName = "Needle 2 Reflex",
                            toolResult = toolRes,
                            latencyMs = latency
                        )
                    )
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-routing direct tool execution failed: ${e.message}", e)
                }
            }
        }

        val isConversationalOrInformational = LanguageNormalizer.isInformational(trimmed)
        val classified = com.pr4nav.jarvis.intent.IntentClassifier.classify(trimmed)

        // 0b. Direct Math & Instant Conversational Answer (<5ms)
        if (classified.responseType == com.pr4nav.jarvis.intent.ResponseType.ANSWER && classified.directAnswer != null) {
            val latency = System.currentTimeMillis() - t0
            val answer = classified.directAnswer
            val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Router: Direct Deterministic Answer\n• Mode: ${mode.displayName}\n• Latency: ${latency}ms\n</think>"
            if (onEvent == null) onChunk?.invoke(answer)
                                emitTurn(onEvent, thinkTrace, answer, "JARVIS Core", latency, true);
            onResult(
                UnifiedExecutionResult(
                    handled = true,
                    source = ExecutionSource.DETERMINISTIC_NEEDLE,
                    speechResponse = answer,
fullSummary = "$thinkTrace\n\n⚡ [Deterministic Answer · ${latency}ms]\n$answer",
                    thinkingTrace = thinkTrace,
                    modelName = "JARVIS Core",
                    latencyMs = latency
                )
            )
            return
        }

        // =========================================================================
        // Tier 1: Deterministic Needle 2 Reflex (<15ms) - Active in all modes
        // =========================================================================
        if (!isConversationalOrInformational) {
            onStatus?.invoke("⚡ Evaluating deterministic reflex & Needle 2...")

            val normalized = LanguageNormalizer.normalize(trimmed)
            if (normalized != null && normalized.confidence >= 0.90f) {
                val validation = com.pr4nav.jarvis.tools.ToolValidator.validate(context, normalized.tool, normalized.args, trimmed)
                if (validation is com.pr4nav.jarvis.tools.ValidationResult.Valid) {
                    try {
                        onStatus?.invoke("⚡ Executing [${normalized.tool}] via Needle Reflex...")
                        val toolRes = CanonicalToolRegistry.execute(context, normalized.tool, normalized.args)
                        if (toolRes.success) {
                            com.pr4nav.jarvis.context.ContextManager.updateToolContext(normalized.tool, normalized.args)
                        } else {
                            com.pr4nav.jarvis.context.ConversationalContext.updateContext(normalized.tool, normalized.args)
                        }
                        val toolDef = CanonicalToolRegistry.get(normalized.tool)
                        val responseMode = com.pr4nav.jarvis.response.AnswerSynthesizer.determineResponseMode(trimmed, classified.category.name)
                        val synthesizedAnswer = com.pr4nav.jarvis.response.AnswerSynthesizer.synthesize(trimmed, normalized.tool, toolRes.data, responseMode)
                        val latency = System.currentTimeMillis() - t0
                        val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Router: Direct deterministic match [${normalized.tool}]\n• Purpose: ${toolDef?.purpose ?: com.pr4nav.jarvis.response.ToolPurpose.ACTION}\n• Mode: ${mode.displayName}\n• Latency: ${latency}ms\n</think>"

                        Log.i(TAG, "Tier 1: Direct deterministic match [${normalized.tool}] in ${latency}ms -> $synthesizedAnswer")
                        if (onEvent == null) onChunk?.invoke(synthesizedAnswer)
                                                        emitTurn(onEvent, thinkTrace, synthesizedAnswer, "Needle 2 Reflex", latency, true,
                            normalized.tool, argSummary(normalized.args),
                            (toolRes.data?.toString() ?: toolRes.error?.message ?: "").take(1200),
                            if (toolRes.success) 0 else 1, latency);
                        onResult(
                            UnifiedExecutionResult(
                                handled = true,
                                source = ExecutionSource.DETERMINISTIC_NEEDLE,
                                speechResponse = synthesizedAnswer,
fullSummary = "$thinkTrace\n\n⚡ [Needle 2 Reflex · ${latency}ms]\n$synthesizedAnswer",
                                thinkingTrace = thinkTrace,
                                modelName = "Needle 2 Reflex",
                                toolResult = toolRes,
                                latencyMs = latency
                            )
                        )
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Deterministic execution failed: ${e.message}", e)
                    }
                }
            }
        }

        // If mode is NEEDLE_ONLY and no tool matched
        if (mode == AgentExecutionMode.NEEDLE_ONLY) {
            val latency = System.currentTimeMillis() - t0
            val msg = "Needle Reflex did not match any device action for \"$trimmed\". Switch to 'Kira AI (Default)' or 'Groq Compound' for open-ended queries."
            if (onEvent == null) onChunk?.invoke(msg)
            emitTurn(onEvent, "Deterministic matching yielded no action.", msg, "Needle 2 Reflex", latency, false);
            onResult(
                UnifiedExecutionResult(
                    handled = false,
                    source = ExecutionSource.DETERMINISTIC_NEEDLE,
                    speechResponse = msg,
                    fullSummary = "[Needle Miss]\n$msg",
                    thinkingTrace = "Deterministic matching yielded no action.",
                    latencyMs = latency
                )
            )
            return
        }

        // =========================================================================
        // Route-aware engine dispatch: mode picks the start engine, the user
        // fallback route decides the chain. Solo routes run a single engine.
        // =========================================================================
        val route = getRoute(context)
        var start: RouteEngine = if (mode == AgentExecutionMode.GROQ_PRIMARY || mode == AgentExecutionMode.GROQ_NEEDLE) {
            RouteEngine.GROQ
        } else {
            RouteEngine.KIRA
        }
        if (!route.contains(start)) {
            start = route.firstOrNull() ?: run {
                emitRouteExhausted(onEvent, onResult, "Fallback route is empty", t0)
                return
            }
        }
        Log.i(TAG, "Dispatch start=$start route=${route.joinToString(",") { it.id }}")
        when (start) {
            RouteEngine.GROQ -> executeGroqWithFallback(context, trimmed, t0, onStatus, onChunk, onEvent, onResult, route)
            RouteEngine.GEMINI -> executeCloudFallback(context, trimmed, t0, onStatus, onChunk, onEvent, onResult)
            RouteEngine.KIRA -> executeKiraWithFallback(context, trimmed, t0, onStatus, onChunk, onEvent, onResult, route)
        }
    }

    private fun executeKiraWithFallback(
        context: Context,
        prompt: String,
        t0: Long,
        onStatus: ((String) -> Unit)?,
        onChunk: ((String) -> Unit)?,
        onEvent: ((AgentStreamEvent) -> Unit)? = null,
        onResult: (UnifiedExecutionResult) -> Unit,
        route: List<RouteEngine>? = null
    ) {
        val rt = route ?: getRoute(context)
        onStatus?.invoke("Querying Kira AI (Full Power)…")
        KiraClient.query(
            context = context,
            prompt = prompt,
            onStatus = onStatus,
            onChunk = onChunk,
            onEvent = onEvent,
            onSuccess = { kiraRes ->
                val latency = System.currentTimeMillis() - t0

                // Guarantee clean response text stripped of thinking tags
                var rawResponse = kiraRes.response.trim()
                if ((rawResponse.isBlank() || rawResponse.equals("null", ignoreCase = true) || rawResponse.equals("null null", ignoreCase = true)) && kiraRes.thinkingTrace.isNotBlank() && !kiraRes.thinkingTrace.equals("null", ignoreCase = true)) {
                    val (_, fallback) = com.pr4nav.jarvis.response.UserResponseSanitizer.stripThinking(kiraRes.thinkingTrace)
                    if (fallback.isNotBlank() && !fallback.equals("null", ignoreCase = true) && !fallback.equals("null null", ignoreCase = true)) {
                        rawResponse = fallback
                    }
                }
                if (rawResponse.isBlank() || rawResponse.equals("null", ignoreCase = true) || rawResponse.equals("null null", ignoreCase = true)) {
                    rawResponse = "Action completed successfully."
                }

                val (_, cleanText) = com.pr4nav.jarvis.response.UserResponseSanitizer.stripThinking(rawResponse)
                val finalAnswer = if (cleanText.isNotBlank() && !cleanText.equals("null", ignoreCase = true) && !cleanText.equals("null null", ignoreCase = true)) cleanText else rawResponse

                val speech = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitizeForSpeech(finalAnswer, prompt)
                com.pr4nav.jarvis.context.ConversationalContext.recordTurn(prompt, speech)

                val toolSummary = if (kiraRes.toolCallsExecuted.isNotEmpty()) {
                    "• Shell/Device Tools: ${kiraRes.toolCallsExecuted.size} (${kiraRes.toolCallsExecuted.map { it.command }.joinToString(", ")})\n"
                } else ""
                val thinkTrace = if (kiraRes.thinkingTrace.isNotBlank() && !kiraRes.thinkingTrace.equals("null", ignoreCase = true)) {
                    kiraRes.thinkingTrace.trim()
                } else {
                    "• Engine: Kira AI (${kiraRes.modelUsed})\n$toolSummary• Latency: ${latency}ms"
                }

                onResult(
                    UnifiedExecutionResult(
                        handled = true,
                        source = ExecutionSource.KIRA_AGENT,
                        jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse(
                            text = finalAnswer,
                            speechText = speech,
                            status = com.pr4nav.jarvis.response.TerminationStatus.FINAL_ANSWER
                        ),
                        speechResponse = speech,
                        fullSummary = "[Kira ${kiraRes.modelUsed} · ${latency}ms]\n\n$finalAnswer",
                        thinkingTrace = thinkTrace,
                        modelName = "Kira AI (${kiraRes.modelUsed})",
                        latencyMs = latency
                    )
                )
            },
            onError = { kiraErr ->
                Log.w(TAG, "Kira cascade failed: $kiraErr")
                when (nextAfter(rt, RouteEngine.KIRA)) {
                    RouteEngine.GROQ -> {
                        onStatus?.invoke("Kira unavailable; falling back to Groq…")
                        executeGroqWithFallback(context, prompt, t0, onStatus, onChunk, onEvent, onResult, rt)
                    }
                    RouteEngine.GEMINI -> {
                        onStatus?.invoke("Kira unavailable and Groq skipped by route; escalating to Cloud…")
                        executeCloudFallback(context, prompt, t0, onStatus, onChunk, onEvent, onResult)
                    }
                    null -> {
                        Log.w(TAG, "Route ends at Kira; no further fallback.")
                        emitRouteExhausted(onEvent, onResult, kiraErr, t0)
                    }
                    RouteEngine.KIRA -> {
                        Log.w(TAG, "Route loops back to Kira; stopping to avoid a cycle.")
                        emitRouteExhausted(onEvent, onResult, kiraErr, t0)
                    }
                }
            }
        )
    }

    private fun executeGroqWithFallback(
        context: Context,
        prompt: String,
        t0: Long,
        onStatus: ((String) -> Unit)?,
        onChunk: ((String) -> Unit)?,
        onEvent: ((AgentStreamEvent) -> Unit)? = null,
        onResult: (UnifiedExecutionResult) -> Unit,
        route: List<RouteEngine>? = null
    ) {
        val rt = route ?: getRoute(context)
        onStatus?.invoke("Asking Groq Compound Agent…")
        GroqClient.query(
            context = context,
            prompt = prompt,
            onSuccess = { groqRes ->
                val latency = System.currentTimeMillis() - t0

                val (_, cleanText) = com.pr4nav.jarvis.response.UserResponseSanitizer.stripThinking(groqRes.response)
                val candidate = if (cleanText.isNotBlank() && !cleanText.equals("null", ignoreCase = true)) cleanText else groqRes.response.trim()
                val finalAnswer = if (candidate.isBlank() || candidate.equals("null", ignoreCase = true) || candidate.equals("null null", ignoreCase = true)) "Action completed successfully." else candidate
                val speech = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitizeForSpeech(finalAnswer, prompt)
                com.pr4nav.jarvis.context.ConversationalContext.recordTurn(prompt, speech)

                if (onEvent != null) {
                    for (rec in groqRes.toolCallsExecuted) {
                        onEvent(AgentStreamEvent.ToolStart(rec.toolName, "", rec.command))
                        onEvent(
                            AgentStreamEvent.ToolEnd(
                                tool = rec.toolName, label = "", detail = rec.command,
                                output = rec.output, exitCode = rec.exitCode,
                                durationMs = rec.durationMs, verified = rec.verified
                            )
                        )
                    }
                }
                emitTurn(onEvent, groqRes.thinkingTrace, finalAnswer,
                    "Groq ${groqRes.modelUsed}", latency, true)

                val toolSummary = if (groqRes.toolCallsExecuted.isNotEmpty()) {
                    "• Shell Tools: ${groqRes.toolCallsExecuted.size} (${groqRes.toolCallsExecuted.map { it.command }.joinToString(", ")})\n"
                } else ""
                val thinkTrace = if (groqRes.thinkingTrace.isNotBlank() && !groqRes.thinkingTrace.equals("null", ignoreCase = true)) {
                    groqRes.thinkingTrace.trim()
                } else {
                    "• Engine: Groq (${groqRes.modelUsed})\n$toolSummary• Latency: ${latency}ms"
                }

                onResult(
                    UnifiedExecutionResult(
                        handled = true,
                        source = ExecutionSource.GROQ_AGENT,
                        jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse(
                            text = finalAnswer,
                            speechText = speech,
                            status = com.pr4nav.jarvis.response.TerminationStatus.FINAL_ANSWER
                        ),
                        speechResponse = speech,
                        fullSummary = "[Groq ${groqRes.modelUsed} · ${latency}ms]\n\n$finalAnswer",
                        thinkingTrace = thinkTrace,
                        modelName = "Groq ${groqRes.modelUsed}",
                        latencyMs = latency
                    )
                )
            },
            onError = { groqErr ->
                Log.w(TAG, "Groq query failed: $groqErr")
                when (nextAfter(rt, RouteEngine.GROQ)) {
                    RouteEngine.KIRA -> {
                        onStatus?.invoke("Groq unavailable; falling back to Kira…")
                        executeKiraWithFallback(context, prompt, t0, onStatus, onChunk, onEvent, onResult, rt)
                    }
                    RouteEngine.GEMINI -> {
                        onStatus?.invoke("Groq unavailable; escalating to Cloud fallback...")
                        executeCloudFallback(context, prompt, t0, onStatus, onChunk, onEvent, onResult)
                    }
                    null -> {
                        Log.w(TAG, "Route ends at Groq; no further fallback.")
                        emitRouteExhausted(onEvent, onResult, groqErr, t0)
                    }
                    RouteEngine.GROQ -> {
                        Log.w(TAG, "Route loops back to Groq; stopping to avoid a cycle.")
                        emitRouteExhausted(onEvent, onResult, groqErr, t0)
                    }
                }
            }
        )
    }

    private fun executeCloudFallback(
        context: Context,
        query: String,
        t0: Long,
        onStatus: ((String) -> Unit)?,
        onChunk: ((String) -> Unit)?,
        onEvent: ((AgentStreamEvent) -> Unit)? = null,
        onResult: (UnifiedExecutionResult) -> Unit
    ) {
        onStatus?.invoke("Escalating to Cloud Reasoning (Gemini)...")
        val geminiChunk: ((String) -> Unit)? = if (onEvent != null) {
            { t -> if (t.isNotBlank()) onEvent(AgentStreamEvent.TextDelta(t)) }
        } else {
            onChunk
        }
        GeminiCloudLLM.generate(
            context = context,
            prompt = query,
            onChunk = geminiChunk,
            onSuccess = { reply ->
                val latency = System.currentTimeMillis() - t0
                val cleaned = GeminiCloudLLM.cleanForSpeech(reply)
                val safeAnswer = if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true) || cleaned.equals("null null", ignoreCase = true)) "Action completed successfully." else cleaned
                val thinkTrace = "<think>\n• Fallback Engine: Google Gemini 2.0 Flash\n• Latency: ${latency}ms\n</think>"
                emitTurn(onEvent, thinkTrace, safeAnswer, "Gemini 2.0 Flash", latency, true)
                onResult(
                    UnifiedExecutionResult(
                        handled = true,
                        source = ExecutionSource.CLOUD_LLM,
                        jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse.of(safeAnswer),
                        speechResponse = safeAnswer,
                        fullSummary = "$thinkTrace\n\n[Gemini 2.0 Flash · ${latency}ms]\n$safeAnswer",
                        thinkingTrace = thinkTrace,
                        modelName = "Gemini 2.0 Flash",
                        latencyMs = latency
                    )
                )
            },
            onError = { err ->
                val latency = System.currentTimeMillis() - t0
                val errMsg = "I'm sorry, I cannot connect to the assistant services right now."
                emitTurn(onEvent, "Fallback Failed: $err", errMsg, "None (Unavailable)", latency, false)
                onResult(
                    UnifiedExecutionResult(
                        handled = false,
                        source = ExecutionSource.FALLBACK,
                        jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse.of(errMsg),
                        speechResponse = errMsg,
                        fullSummary = "Primary models (Kira & Groq) and cloud reasoning are currently unavailable: $err",
                        thinkingTrace = "<think>\n• Fallback Failed: $err\n</think>",
                        modelName = "None (Unavailable)",
                        latencyMs = latency
                    )
                )
            }
        )
    }
}
