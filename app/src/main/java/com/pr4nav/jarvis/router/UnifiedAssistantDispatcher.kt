package com.pr4nav.jarvis.router

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.llm.LocalLLM
import com.pr4nav.jarvis.llm.LocalModelManager
import com.pr4nav.jarvis.llm.QwenLocalLLM
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class ExecutionSource(val label: String, val badge: String) {
    DETERMINISTIC_NEEDLE("Needle 2 Deterministic", "⚡ [Needle 2 Deterministic]"),
    AGY_AGENT("AGY Autonomous Agent", "🤖 [AGY Agent (PRoot Linux)]"),
    LOCAL_LLM("🟢 Local Qwen2.5-1.5B", "🟢 [Local: Qwen2.5-1.5B]"),
    CLOUD_LLM("Gemini 2.0 Flash (Cloud)", "☁️ [Gemini 2.0 Flash (Cloud)]"),
    FALLBACK("Local Fallback", "⚙️ [Local System Fallback]")
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
 * Executes the user-aligned reasoning hierarchy:
 *
 * 1. Tier 1: Deterministic Needle 2 Reflex (<15ms) for device actions & tool execution.
 * 2. Tier 2 (PRIMARY): AGY Autonomous Agent (PRoot Ubuntu :5050 / `agy -p`) & Local Qwen3.5-2B SLM.
 * 3. Tier 3 (FALLBACK ONLY): Cloud Gemini 2.0 Flash used ONLY when AGY has no confidence or takes >30s.
 */
object UnifiedAssistantDispatcher {

    private const val TAG = "UnifiedAssistant"

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

        // Initialize Canonical Tools
        CanonicalToolRegistry.init(context)

        val isConversationalOrInformational = LanguageNormalizer.isInformational(trimmed)

        // Intent Classification & Answer / Information Pre-check
        val classified = com.pr4nav.jarvis.intent.IntentClassifier.classify(trimmed)

        // 0. Direct Math & Conversational Answer Path (<5ms)
        if (classified.responseType == com.pr4nav.jarvis.intent.ResponseType.ANSWER && classified.directAnswer != null) {
            val latency = System.currentTimeMillis() - t0
            val answer = classified.directAnswer
            val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Router: Direct Deterministic Answer\n• Category: ${classified.category}\n• Latency: ${latency}ms\n</think>"
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
        // Tier 1: Deterministic Fast-Path (<15ms)
        // =========================================================================
        if (!isConversationalOrInformational) {
            onStatus?.invoke("⚡ Evaluating deterministic tools & Needle 2...")

            val normalized = LanguageNormalizer.normalize(trimmed)
            if (normalized != null && normalized.confidence >= 0.90f) {
                val validation = com.pr4nav.jarvis.tools.ToolValidator.validate(context, normalized.tool, normalized.args, trimmed)
                if (validation is com.pr4nav.jarvis.tools.ValidationResult.Valid) {
                    try {
                        onStatus?.invoke("⚡ Executing canonical tool [${normalized.tool}]...")
                        val toolRes = CanonicalToolRegistry.execute(context, normalized.tool, normalized.args)
                        com.pr4nav.jarvis.context.ConversationalContext.updateContext(normalized.tool, normalized.args)
                        val toolDef = CanonicalToolRegistry.get(normalized.tool)
                        val responseMode = com.pr4nav.jarvis.response.AnswerSynthesizer.determineResponseMode(trimmed, classified.category.name)
                        val synthesizedAnswer = com.pr4nav.jarvis.response.AnswerSynthesizer.synthesize(trimmed, normalized.tool, toolRes.data, responseMode)
                        val terminationStatus = if (toolDef?.purpose == com.pr4nav.jarvis.response.ToolPurpose.ACTION) com.pr4nav.jarvis.response.TerminationStatus.ACTION_COMPLETED else com.pr4nav.jarvis.response.TerminationStatus.FINAL_ANSWER
                        val latency = System.currentTimeMillis() - t0
                        val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Router: Direct deterministic match [${normalized.tool}]\n• Purpose: ${toolDef?.purpose ?: com.pr4nav.jarvis.response.ToolPurpose.ACTION}\n• Termination: $terminationStatus\n• Qwen: SKIPPED (Deterministic priority)\n• AGY: SKIPPED\n• Args: ${normalized.args}\n</think>"

                        Log.i(TAG, "Tier 1: Direct deterministic match [${normalized.tool}] in ${latency}ms -> $synthesizedAnswer")
                        onChunk?.invoke(synthesizedAnswer)
                        onResult(
                            UnifiedExecutionResult(
                                handled = true,
                                source = ExecutionSource.DETERMINISTIC_NEEDLE,
                                speechResponse = synthesizedAnswer,
                                fullSummary = "$thinkTrace\n\n⚡ [Needle 2 Deterministic · ${latency}ms]\n$synthesizedAnswer",
                                thinkingTrace = thinkTrace,
                                modelName = "Needle 2 Engine",
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

            // Check Local Qwen2.5-1.5B if installed
            val qwen = QwenLocalLLM(context)
            val activeModelId = LocalModelManager.getActiveModelId(context)
            if (LocalModelManager.isModelInstalled(context, activeModelId)) {
                try {
                    Log.i(TAG, "Checking Local Qwen2.5-1.5B SLM...")
                    onStatus?.invoke("🟢 Asking Local Qwen2.5-1.5B on-device model...")
                    val future = qwen.generate(trimmed, timeoutMs = 4_000L)
                    val llmRes = future.get(4_000L, TimeUnit.MILLISECONDS)

                    if (llmRes.toolCall != null && llmRes.toolCall != "escalate") {
                        val validation = com.pr4nav.jarvis.tools.ToolValidator.validate(
                            context,
                            llmRes.toolCall,
                            llmRes.args ?: JSONObject(),
                            trimmed
                        )

                        if (validation is com.pr4nav.jarvis.tools.ValidationResult.Valid) {
                            onStatus?.invoke("🟢 Executing [${llmRes.toolCall}] via Qwen2.5-1.5B...")
                            val args = llmRes.args ?: JSONObject()
                            val toolDef = validation.toolDef
                            val execRes = toolDef.executeWithTimeout(context, args)
                            val responseMode = com.pr4nav.jarvis.response.AnswerSynthesizer.determineResponseMode(trimmed, classified.category.name)
                            val synthesizedAnswer = com.pr4nav.jarvis.response.AnswerSynthesizer.synthesize(trimmed, llmRes.toolCall, execRes.data, responseMode)
                            val terminationStatus = if (toolDef.purpose == com.pr4nav.jarvis.response.ToolPurpose.ACTION) com.pr4nav.jarvis.response.TerminationStatus.ACTION_COMPLETED else com.pr4nav.jarvis.response.TerminationStatus.FINAL_ANSWER
                            val latency = System.currentTimeMillis() - t0
                            val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Evaluator: 🟢 Local Qwen2.5-1.5B\n• Decision: Validated on-device capability [${llmRes.toolCall}]\n• Purpose: ${toolDef.purpose}\n• Termination: $terminationStatus\n• Score: ${validation.score}/100\n• Execution: Success\n</think>"

                            onChunk?.invoke(synthesizedAnswer)
                            onResult(
                                UnifiedExecutionResult(
                                    handled = true,
                                    source = ExecutionSource.LOCAL_LLM,
                                    speechResponse = synthesizedAnswer,
                                    fullSummary = "$thinkTrace\n\n🟢 [Local: Qwen2.5-1.5B · ${latency}ms]\n$synthesizedAnswer",
                                    thinkingTrace = thinkTrace,
                                    modelName = "🟢 Local Qwen2.5-1.5B",
                                    toolResult = execRes,
                                    latencyMs = latency
                                )
                            )
                            return
                        } else if (validation is com.pr4nav.jarvis.tools.ValidationResult.Rejected) {
                            Log.w(TAG, "Qwen proposed tool [${llmRes.toolCall}] REJECTED by semantic guard: ${validation.error.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Local Qwen2.5-1.5B check passed: ${e.message}")
                }
            }
        }

        // =========================================================================
        // Tier 2 (PRIMARY): Query AGY Autonomous Agent (:5050 Daemon & PRoot CLI)
        // =========================================================================
        Log.i(TAG, "Tier 2: Querying AGY Autonomous Agent as PRIMARY intelligence...")
        onStatus?.invoke("🤖 Querying AGY Autonomous Agent (PRoot Linux)...")

        Thread {
            try {
                val agyClient = com.pr4nav.jarvis.agy.AgyClient()
                var agyAnswer = ""
                var agySuccess = false

                // Execute via AGY in PRoot Ubuntu (30s max timeout)
                val agyRes = com.pr4nav.jarvis.Shell.agy(trimmed, timeoutMs = 30_000)
                if (agyRes.rc == 0 && agyRes.out.isNotBlank()) {
                    agyAnswer = GeminiCloudLLM.cleanForSpeech(agyRes.out)
                    agySuccess = true
                }

                if (agySuccess && agyAnswer.isNotBlank()) {
                    val latency = System.currentTimeMillis() - t0
                    com.pr4nav.jarvis.context.ConversationalContext.recordTurn(trimmed, agyAnswer)

                    // Stream words
                    val words = agyAnswer.split(" ")
                    for (w in words) {
                        onChunk?.invoke("$w ")
                        try { Thread.sleep(10) } catch (_: Exception) {}
                    }

                    val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Primary Engine: AGY Autonomous Agent (PRoot Linux)\n• Session: Authenticated Antigravity Daemon\n• Latency: ${latency}ms\n</think>"

                    Log.i(TAG, "Tier 2: AGY Autonomous Agent succeeded in ${latency}ms")
                    onResult(
                        UnifiedExecutionResult(
                            handled = true,
                            source = ExecutionSource.AGY_AGENT,
                            speechResponse = agyAnswer,
                            fullSummary = "$thinkTrace\n\n🤖 [AGY Agent (PRoot Linux) · ${latency}ms]\n$agyAnswer",
                            thinkingTrace = thinkTrace,
                            modelName = "AGY Agent (PRoot Linux)",
                            latencyMs = latency
                        )
                    )
                    return@Thread
                }
            } catch (e: Exception) {
                Log.w(TAG, "AGY Primary query timed out or failed (${e.message}), escalating to Cloud LLM fallback...")
            }

            // =========================================================================
            // Tier 3 (FALLBACK ONLY): Google Gemini Cloud API (Only if AGY unavailable / >30s)
            // =========================================================================
            Log.i(TAG, "Tier 3: AGY unavailable or timed out; escalating to Cloud LLM Fallback (Gemini 2.0 Flash)...")
            onStatus?.invoke("☁️ Falling back to Google Gemini 2.0 Flash (Cloud)...")

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

                    val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Primary (AGY): Timed out or unreachable\n• Fallback: Google Gemini 2.0 Flash (Cloud)\n• Latency: ${latency}ms\n</think>"

                    Log.i(TAG, "Tier 3: Cloud Gemini 2.0 Flash fallback responded in ${latency}ms")
                    onResult(
                        UnifiedExecutionResult(
                            handled = true,
                            source = ExecutionSource.CLOUD_LLM,
                            speechResponse = cloudSpeech,
                            fullSummary = "$thinkTrace\n\n☁️ [Gemini 2.0 Flash (Cloud Fallback) · ${latency}ms]\n$cloudSpeech",
                            thinkingTrace = thinkTrace,
                            modelName = "Gemini 2.0 Flash (Cloud Fallback)",
                            latencyMs = latency
                        )
                    )
                },
                onError = { errMsg ->
                    val latency = System.currentTimeMillis() - t0
                    Log.e(TAG, "Cloud fallback failed: $errMsg")

                    val helpfulFallback = when {
                        trimmed.lowercase().contains("who are you") || trimmed.lowercase().contains("what is your name") ->
                            "I am JARVIS, your personal autonomous on-device AI assistant."
                        trimmed.lowercase().contains("how are you") ->
                            "All systems are operating at peak performance, sir. How can I assist you today?"
                        trimmed.lowercase().contains("hi") || trimmed.lowercase().contains("hello") || trimmed.lowercase().contains("hey") ->
                            "Hello! Systems online and ready. What would you like to do?"
                        trimmed.lowercase().contains("time") ->
                            "The current time is ${java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())}."
                        trimmed.lowercase().contains("date") ->
                            "Today is ${java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date())}."
                        else ->
                            "I am operating in local offline mode right now. You can ask me to open apps, control volume, trigger flashlight, manage files, or check device status."
                    }

                    val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Primary (AGY) & Cloud: Unavailable ($errMsg)\n• Fallback: Native system conversational handler\n</think>"
                    onChunk?.invoke(helpfulFallback)

                    onResult(
                        UnifiedExecutionResult(
                            handled = false,
                            source = ExecutionSource.FALLBACK,
                            speechResponse = helpfulFallback,
                            fullSummary = "$thinkTrace\n\n⚙️ [Local System Fallback · ${latency}ms]\n$helpfulFallback",
                            thinkingTrace = thinkTrace,
                            modelName = "Local System Fallback",
                            latencyMs = latency
                        )
                    )
                }
            )
        }.start()
    }
}

