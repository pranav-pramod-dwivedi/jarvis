package com.pr4nav.jarvis.router

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.llm.GroqClient
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONObject

enum class AgentExecutionMode(val displayName: String, val badge: String, val description: String) {
    AUTO("Auto (Needle + Groq + AGY)", "[Auto Tri-Tier]", "Cascades Needle Reflex -> Groq Compound Mini -> AGY Autonomous Agent"),
    CLOUD_NEEDLE("Cloud + Needle", "[Cloud + Needle]", "Gemini 2.0 Flash + Needle Reflex"),
    GROQ_NEEDLE("Groq + Needle", "[Groq + Needle]", "Groq Compound Mini Agent + Needle Reflex"),
    NEEDLE_ONLY("Needle Only", "[Needle Only]", "Fast Deterministic On-Device Actions")
}

enum class ExecutionSource(val label: String, val badge: String) {
    DETERMINISTIC_NEEDLE("Needle 2 Reflex", "[Needle 2 Reflex]"),
    GROQ_AGENT("Groq Compound Agent", "[Groq Agent]"),
    AGY_AGENT("AGY Autonomous Agent", "[AGY Agent (PRoot Linux)]"),
    CLOUD_LLM("Gemini 2.0 Flash (Cloud)", "[Gemini 2.0 Flash (Cloud)]"),
    FALLBACK("System Fallback", "[System Fallback]")
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
 * 1. CLOUD_NEEDLE (Default): Needle Reflex -> AGY -> Gemini 2.0 Flash
 * 2. NEEDLE_ONLY: Needle Reflex exclusively (Fast on-device execution)
 * 3. QWEN_NEEDLE: Needle Reflex -> Local Qwen3.5-2B Llama Agent Server
 */
object UnifiedAssistantDispatcher {

    private const val TAG = "UnifiedAssistant"
    private const val PREFS_NAME = "jarvis_mode_prefs"
    private const val KEY_SELECTED_MODE = "jarvis_agent_mode"
    @Volatile private var inMemoryMode: AgentExecutionMode = AgentExecutionMode.AUTO

    fun getAgentMode(context: Context?): AgentExecutionMode {
        if (context == null) return inMemoryMode
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs?.getString(KEY_SELECTED_MODE, inMemoryMode.name)
            if (name != null) AgentExecutionMode.valueOf(name) else inMemoryMode
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

    fun execute(
        context: Context,
        rawQuery: String,
        onStatus: ((String) -> Unit)? = null,
        onChunk: ((String) -> Unit)? = null,
        onResult: (UnifiedExecutionResult) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        val resolvedQuery = com.pr4nav.jarvis.context.ConversationalContext.resolvePronouns(rawQuery)
        val trimmed = resolvedQuery.trim()

        if (trimmed.isEmpty()) {
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
            onChunk?.invoke(blockMsg)
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
                    onChunk?.invoke(synthesizedAnswer)
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
            onChunk?.invoke(answer)
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
                        onChunk?.invoke(synthesizedAnswer)
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
            val msg = "Needle Reflex did not match any device action for \"$trimmed\". Switch to 'Cloud + Needle' or 'Groq + Needle' for open-ended queries."
            onChunk?.invoke(msg)
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
        // GROQ_NEEDLE Mode: Groq LLaMA 3.3 70B Agent with Shell Capabilities
        // =========================================================================
        if (mode == AgentExecutionMode.GROQ_NEEDLE) {
            Log.i(TAG, "Querying Groq Compound Agent...")
            onStatus?.invoke("Asking Groq Compound...")

            GroqClient.query(
                context = context,
                prompt = trimmed,
                onSuccess = { groqRes ->
                    val latency = System.currentTimeMillis() - t0
                    val cleaned = GeminiCloudLLM.cleanForSpeech(groqRes.response)
                    com.pr4nav.jarvis.context.ConversationalContext.recordTurn(trimmed, cleaned)

                    val toolSummary = if (groqRes.toolCallsExecuted.isNotEmpty()) {
                        "• Shell Tools Executed: ${groqRes.toolCallsExecuted.size} (${groqRes.toolCallsExecuted.map { it.command }.joinToString(", ")})\n"
                    } else ""
                    val thinkTrace = if (groqRes.thinkingTrace.isNotBlank()) {
                        "<think>\n${groqRes.thinkingTrace}\n$toolSummary• Latency: ${latency}ms\n• Engine: Groq (${groqRes.modelUsed})\n</think>"
                    } else {
                        "<think>\n• Input: \"$trimmed\"\n• Engine: Groq (${groqRes.modelUsed})\n$toolSummary• Latency: ${latency}ms\n</think>"
                    }

                    onChunk?.invoke(cleaned)
                    onResult(
                        UnifiedExecutionResult(
                            handled = true,
                            source = if (groqRes.escalatedToAgy) ExecutionSource.AGY_AGENT else ExecutionSource.GROQ_AGENT,
                            speechResponse = cleaned,
                            fullSummary = "$thinkTrace\n\n[Groq ${groqRes.modelUsed} · ${latency}ms]\n$cleaned",
                            thinkingTrace = thinkTrace,
                            modelName = if (groqRes.escalatedToAgy) "AGY Autonomous Agent" else "Groq ${groqRes.modelUsed}",
                            latencyMs = latency
                        )
                    )
                },
                onError = { err ->
                    val latency = System.currentTimeMillis() - t0
                    Log.w(TAG, "Groq query failed: $err; escalating to Cloud/AGY fallback")
                    executeCloudFallback(context, trimmed, t0, onStatus, onChunk, onResult)
                }
            )
            return
        }

        // =========================================================================
        // AUTO Mode: Tri-Tier Cascade (Needle -> Groq LLaMA -> AGY / Cloud Gemini)
        // =========================================================================
        if (mode == AgentExecutionMode.AUTO) {
            Log.i(TAG, "Executing Auto Tri-Tier: Testing Tier 2 (Groq Compound) then Tier 3 (AGY / Cloud)...")
            onStatus?.invoke("Querying Groq (Tier 2)...")

            GroqClient.query(
                context = context,
                prompt = trimmed,
                onSuccess = { groqRes ->
                    val latency = System.currentTimeMillis() - t0
                    val cleaned = GeminiCloudLLM.cleanForSpeech(groqRes.response)
                    if (cleaned.isNotBlank()) {
                        com.pr4nav.jarvis.context.ConversationalContext.recordTurn(trimmed, cleaned)
                        val toolSummary = if (groqRes.toolCallsExecuted.isNotEmpty()) {
                            "• Shell Tools Executed: ${groqRes.toolCallsExecuted.size} (${groqRes.toolCallsExecuted.map { it.command }.joinToString(", ")})\n"
                        } else ""
                        val thinkTrace = if (groqRes.thinkingTrace.isNotBlank()) {
                            "<think>\n${groqRes.thinkingTrace}\n$toolSummary• Mode: Auto Tri-Tier\n• Resolved At: Tier 2 (Groq)\n• Latency: ${latency}ms\n</think>"
                        } else {
                            "<think>\n• Input: \"$trimmed\"\n• Mode: Auto Tri-Tier\n• Resolved At: Tier 2 (Groq)\n• Latency: ${latency}ms\n</think>"
                        }

                        onChunk?.invoke(cleaned)
                        onResult(
                            UnifiedExecutionResult(
                                handled = true,
                                source = if (groqRes.escalatedToAgy) ExecutionSource.AGY_AGENT else ExecutionSource.GROQ_AGENT,
                                speechResponse = cleaned,
                                fullSummary = "$thinkTrace\n\n[Groq ${groqRes.modelUsed} · ${latency}ms]\n$cleaned",
                                thinkingTrace = thinkTrace,
                                modelName = if (groqRes.escalatedToAgy) "AGY Autonomous Agent" else "Groq ${groqRes.modelUsed}",
                                latencyMs = latency
                            )
                        )
                        return@query
                    }

                    // Empty response -> Escalate to fallback ONCE
                    Log.i(TAG, "Groq returned empty response; escalating sequentially to Tier 3 (AGY / Cloud)...")
                    onStatus?.invoke("Escalating to Tier 3 (AGY / Cloud)...")
                    executeCloudFallback(context, trimmed, t0, onStatus, onChunk, onResult)
                },
                onError = { groqErr ->
                    Log.i(TAG, "Groq unavailable ($groqErr); escalating to Tier 3 (AGY / Cloud)...")
                    onStatus?.invoke("Groq unavailable; escalating to Tier 3 (AGY / Cloud)...")
                    executeCloudFallback(context, trimmed, t0, onStatus, onChunk, onResult)
                }
            )
            return
        }

        // =========================================================================
        // CLOUD_NEEDLE Mode: Direct Cloud Gemini 2.0 Flash / AGY
        // =========================================================================
        Log.i(TAG, "Routing via central JarvisRouter / Cloud...")
        executeCloudFallback(context, trimmed, t0, onStatus, onChunk, onResult)
    }

    private fun executeCloudFallback(
        context: Context,
        query: String,
        t0: Long,
        onStatus: ((String) -> Unit)?,
        onChunk: ((String) -> Unit)?,
        onResult: (UnifiedExecutionResult) -> Unit
    ) {
        onStatus?.invoke("Escalating to Cloud Reasoning (Gemini)...")
        GeminiCloudLLM.generate(
            context = context,
            prompt = query,
            onChunk = onChunk,
            onSuccess = { reply ->
                val latency = System.currentTimeMillis() - t0
                val cleaned = GeminiCloudLLM.cleanForSpeech(reply)
                val thinkTrace = "<think>\n• Fallback Engine: Google Gemini 2.0 Flash\n• Latency: ${latency}ms\n</think>"
                onResult(
                    UnifiedExecutionResult(
                        handled = true,
                        source = ExecutionSource.CLOUD_LLM,
                        jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse.of(cleaned),
                        speechResponse = cleaned,
                        fullSummary = "$thinkTrace\n\n[Gemini 2.0 Flash · ${latency}ms]\n$cleaned",
                        thinkingTrace = thinkTrace,
                        modelName = "Gemini 2.0 Flash",
                        latencyMs = latency
                    )
                )
            },
            onError = { err ->
                val latency = System.currentTimeMillis() - t0
                val errMsg = "I'm sorry, I cannot connect to the assistant services right now."
                onResult(
                    UnifiedExecutionResult(
                        handled = false,
                        source = ExecutionSource.FALLBACK,
                        jarvisResponse = com.pr4nav.jarvis.response.JarvisResponse.of(errMsg),
                        speechResponse = errMsg,
                        fullSummary = "Cloud reasoning and local AGY are currently unavailable: $err",
                        thinkingTrace = "<think>\n• Fallback Failed: $err\n</think>",
                        modelName = "None (Unavailable)",
                        latencyMs = latency
                    )
                )
            }
        )
    }
}
