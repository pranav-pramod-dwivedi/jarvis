package com.pr4nav.jarvis.router

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.llm.QwenAgentClient
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONObject

enum class AgentExecutionMode(val displayName: String, val badge: String, val description: String) {
    CLOUD_NEEDLE("☁️ Cloud + ⚡ Needle", "☁️ [Cloud + Needle]", "Gemini 2.0 Flash + Needle Reflex"),
    NEEDLE_ONLY("⚡ Needle Only", "⚡ [Needle Only]", "Fast Deterministic On-Device Actions"),
    QWEN_NEEDLE("🟢 Qwen + ⚡ Needle", "🟢 [Qwen + Needle]", "Local Qwen3.5-2B Shell Agent + Needle Reflex")
}

enum class ExecutionSource(val label: String, val badge: String) {
    DETERMINISTIC_NEEDLE("Needle 2 Reflex", "⚡ [Needle 2 Reflex]"),
    QWEN_AGENT("Qwen3.5-2B Shell Agent", "🟢 [Qwen 3.5 Agent]"),
    AGY_AGENT("AGY Autonomous Agent", "🤖 [AGY Agent (PRoot Linux)]"),
    CLOUD_LLM("Gemini 2.0 Flash (Cloud)", "☁️ [Gemini 2.0 Flash (Cloud)]"),
    FALLBACK("System Fallback", "⚙️ [System Fallback]")
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

    fun getAgentMode(context: Context?): AgentExecutionMode {
        if (context == null) return AgentExecutionMode.CLOUD_NEEDLE
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_SELECTED_MODE, AgentExecutionMode.CLOUD_NEEDLE.name)
        return try {
            AgentExecutionMode.valueOf(name ?: AgentExecutionMode.CLOUD_NEEDLE.name)
        } catch (_: Exception) {
            AgentExecutionMode.CLOUD_NEEDLE
        }
    }

    fun setAgentMode(context: Context, mode: AgentExecutionMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_MODE, mode.name).apply()
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

        val isConversationalOrInformational = LanguageNormalizer.isInformational(trimmed)
        val classified = com.pr4nav.jarvis.intent.IntentClassifier.classify(trimmed)

        // 0. Direct Math & Instant Conversational Answer (<5ms)
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
                        com.pr4nav.jarvis.context.ConversationalContext.updateContext(normalized.tool, normalized.args)
                        val toolDef = CanonicalToolRegistry.get(normalized.tool)
                        val responseMode = com.pr4nav.jarvis.response.AnswerSynthesizer.determineResponseMode(trimmed, classified.category.name)
                        val synthesizedAnswer = com.pr4nav.jarvis.response.AnswerSynthesizer.synthesize(trimmed, normalized.tool, toolRes.data, responseMode)
                        val terminationStatus = if (toolDef?.purpose == com.pr4nav.jarvis.response.ToolPurpose.ACTION) com.pr4nav.jarvis.response.TerminationStatus.ACTION_COMPLETED else com.pr4nav.jarvis.response.TerminationStatus.FINAL_ANSWER
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
            val msg = "Needle Reflex did not match any device action for \"$trimmed\". Switch to 'Cloud + Needle' or 'Qwen + Needle' for open-ended queries."
            onChunk?.invoke(msg)
            onResult(
                UnifiedExecutionResult(
                    handled = false,
                    source = ExecutionSource.FALLBACK,
                    speechResponse = msg,
                    fullSummary = "⚡ [Needle Only]\n$msg",
                    thinkingTrace = "Needle Only mode active; open-ended reasoning skipped.",
                    latencyMs = latency
                )
            )
            return
        }

        // =========================================================================
        // QWEN_NEEDLE Mode: Local Qwen3.5-2B Shell Agent Server
        // =========================================================================
        if (mode == AgentExecutionMode.QWEN_NEEDLE) {
            Log.i(TAG, "Querying Local Qwen3.5-2B Shell Agent Server (:8081)...")
            onStatus?.invoke("🟢 Asking Local Qwen3.5-2B Shell Agent...")

            QwenAgentClient.query(
                context = context,
                prompt = trimmed,
                onSuccess = { qwenRes ->
                    val latency = System.currentTimeMillis() - t0
                    val cleaned = GeminiCloudLLM.cleanForSpeech(qwenRes.response)
                    com.pr4nav.jarvis.context.ConversationalContext.recordTurn(trimmed, cleaned)

                    val toolSummary = if (qwenRes.toolCallsExecuted.isNotEmpty()) {
                        "• Shell Tools Executed: ${qwenRes.toolCallsExecuted.size} (${qwenRes.toolCallsExecuted.map { it.command }.joinToString(", ")})\n"
                    } else ""
                    val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Engine: Local Qwen3.5-2B Shell Agent\n$toolSummary• Latency: ${latency}ms\n</think>"

                    onChunk?.invoke(cleaned)
                    onResult(
                        UnifiedExecutionResult(
                            handled = true,
                            source = ExecutionSource.QWEN_AGENT,
                            speechResponse = cleaned,
                            fullSummary = "$thinkTrace\n\n🟢 [Qwen3.5-2B Agent · ${latency}ms]\n$cleaned",
                            thinkingTrace = thinkTrace,
                            modelName = "Qwen3.5-2B Shell Agent",
                            latencyMs = latency
                        )
                    )
                },
                onError = { err ->
                    val latency = System.currentTimeMillis() - t0
                    Log.w(TAG, "Qwen Agent query failed: $err")
                    val fallbackMsg = "Local Qwen Agent is offline. Please make sure `llama serve` and `python3 server/agent.py` are running."
                    onChunk?.invoke(fallbackMsg)
                    onResult(
                        UnifiedExecutionResult(
                            handled = false,
                            source = ExecutionSource.FALLBACK,
                            speechResponse = fallbackMsg,
                            fullSummary = "⚙️ [Qwen Agent Offline]\n$err\n\n$fallbackMsg",
                            thinkingTrace = "Qwen Agent server unreachable.",
                            latencyMs = latency
                        )
                    )
                }
            )
            return
        }

        // =========================================================================
        // CLOUD_NEEDLE Mode: AGY Autonomous Agent & Gemini 2.0 Flash Cloud LLM
        // =========================================================================
        Log.i(TAG, "Querying Cloud LLM (Gemini 2.0 Flash) with zero-refusal command access...")
        onStatus?.invoke("☁️ Querying Google Gemini Cloud LLM...")

        Thread {
            val historyContext = com.pr4nav.jarvis.context.ConversationalContext.getRecentHistory(4)
            val fullPromptWithHistory = if (historyContext.isNotBlank()) {
                "$historyContext\nUser: $trimmed\nJarvis:"
            } else {
                trimmed
            }

            GeminiCloudLLM.generate(
                context = context,
                prompt = fullPromptWithHistory,
                onChunk = { chunk ->
                    onStatus?.invoke("✍️ Writing response...")
                    onChunk?.invoke(chunk)
                },
                onSuccess = { cloudSpeech ->
                    val latency = System.currentTimeMillis() - t0
                    com.pr4nav.jarvis.context.ConversationalContext.recordTurn(trimmed, cloudSpeech)

                    val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Engine: Google Gemini 2.0 Flash (Cloud)\n• Mode: Cloud + Needle\n• Latency: ${latency}ms\n</think>"

                    Log.i(TAG, "Cloud Gemini 2.0 Flash responded in ${latency}ms")
                    onResult(
                        UnifiedExecutionResult(
                            handled = true,
                            source = ExecutionSource.CLOUD_LLM,
                            speechResponse = cloudSpeech,
                            fullSummary = "$thinkTrace\n\n☁️ [Gemini 2.0 Flash · ${latency}ms]\n$cloudSpeech",
                            thinkingTrace = thinkTrace,
                            modelName = "Gemini 2.0 Flash (Cloud)",
                            latencyMs = latency
                        )
                    )
                },
                onError = { errMsg ->
                    val latency = System.currentTimeMillis() - t0
                    Log.e(TAG, "Cloud LLM failed: $errMsg")

                    val helpfulFallback = when {
                        trimmed.lowercase().contains("who are you") || trimmed.lowercase().contains("what is your name") ->
                            "I am JARVIS, your personal autonomous AI assistant."
                        trimmed.lowercase().contains("how are you") ->
                            "All systems are operating at peak performance, sir. How can I assist you today?"
                        trimmed.lowercase().contains("hi") || trimmed.lowercase().contains("hello") || trimmed.lowercase().contains("hey") ->
                            "Hello! Systems online and ready. What would you like to do?"
                        trimmed.lowercase().contains("time") ->
                            "The current time is ${java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())}."
                        trimmed.lowercase().contains("date") ->
                            "Today is ${java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date())}."
                        else ->
                            "I am ready. You can ask me to open apps, control volume, toggle flashlight, run shell commands, or query information."
                    }

                    val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Cloud: $errMsg\n• Fallback: Native system conversational handler\n</think>"
                    onChunk?.invoke(helpfulFallback)

                    onResult(
                        UnifiedExecutionResult(
                            handled = false,
                            source = ExecutionSource.FALLBACK,
                            speechResponse = helpfulFallback,
                            fullSummary = "$thinkTrace\n\n⚙️ [System Fallback · ${latency}ms]\n$helpfulFallback",
                            thinkingTrace = thinkTrace,
                            modelName = "System Fallback",
                            latencyMs = latency
                        )
                    )
                }
            )
        }.start()
    }
}
