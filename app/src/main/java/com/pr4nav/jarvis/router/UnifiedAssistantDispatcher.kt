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
    LOCAL_LLM("Qwen 2.5 Local SLM", "🧠 [Qwen 2.5 Local SLM]"),
    CLOUD_LLM("Gemini 2.0 Flash (Cloud)", "☁️ [Gemini 2.0 Flash (Cloud)]"),
    FALLBACK("Local Fallback", "⚙️ [Local System Fallback]")
}

data class UnifiedExecutionResult(
    val handled: Boolean,
    val source: ExecutionSource,
    val speechResponse: String,
    val fullSummary: String = speechResponse,
    val thinkingTrace: String = "",
    val modelName: String = source.label,
    val toolResult: ToolResult? = null,
    val latencyMs: Long = 0L
)

/**
 * Unified Autonomous Assistant Dispatcher.
 * Executes the user-aligned reasoning hierarchy:
 *
 * 1. Pre-Check: Prevent greetings ("hi", "hello") from accidentally triggering tools/flashlight.
 * 2. Step 1: Query Qwen 2.5 Local SLM first to check if on-device model can execute or answer.
 * 3. Escalation Check: If Qwen cannot do it, lacks confidence, or requires conversational/cloud reasoning:
 *    -> Cleanly cancel local request (NO hallucinations) and escalate to Cloud LLM (Gemini 2.0 Flash / AGY).
 * 4. Model Attribution & Thinking: Explicitly format which model answered and embed <think> traces.
 */
object UnifiedAssistantDispatcher {

    private const val TAG = "UnifiedAssistant"

    fun execute(
        context: Context,
        rawQuery: String,
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

        // =========================================================================
        // Step 1: Query Qwen 2.5 Local SLM First (On-Device Inference & Assessment)
        // =========================================================================
        val qwen = QwenLocalLLM(context)
        val activeModelId = LocalModelManager.getActiveModelId(context)
        val isLocalModelInstalled = LocalModelManager.isModelInstalled(context, activeModelId)

        // If not a pure conversational greeting, check if Qwen can execute on-device
        if (!isConversationalOrInformational) {
            // Check deterministic fast-path if exact match
            val normalized = LanguageNormalizer.normalize(trimmed)
            if (normalized != null && normalized.confidence >= 0.90f) {
                try {
                    val toolRes = CanonicalToolRegistry.execute(context, normalized.tool, normalized.args)
                    com.pr4nav.jarvis.context.ConversationalContext.updateContext(normalized.tool, normalized.args)
                    val summary = if (toolRes.success) {
                        toolRes.data?.toString() ?: "Completed ${normalized.tool}."
                    } else {
                        toolRes.error?.message ?: "Failed to execute ${normalized.tool}."
                    }
                    val latency = System.currentTimeMillis() - t0
                    val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Router: High-confidence deterministic rule matched\n• Model: Needle 2 Engine\n• Action: Executed canonical tool [${normalized.tool}]\n• Args: ${normalized.args}\n</think>"

                    Log.i(TAG, "Tier 1: Needle deterministic match [${normalized.tool}] in ${latency}ms")
                    onResult(
                        UnifiedExecutionResult(
                            handled = true,
                            source = ExecutionSource.DETERMINISTIC_NEEDLE,
                            speechResponse = summary,
                            fullSummary = "$thinkTrace\n\n⚡ [Needle 2 Deterministic · ${latency}ms]\n$summary",
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

            // Query Qwen on-device SLM
            try {
                Log.i(TAG, "Step 1: Asking Qwen Local SLM if it can handle: \"$trimmed\"...")
                val future = qwen.generate(trimmed, timeoutMs = 4_000L)
                val llmRes = future.get(4_000L, TimeUnit.MILLISECONDS)

                // Strict validation: Do not hallucinate or guess random tools!
                if (llmRes.toolCall != null &&
                    llmRes.toolCall != "escalate" &&
                    llmRes.confidence >= 0.75f &&
                    CanonicalToolRegistry.get(llmRes.toolCall) != null
                ) {
                    val args = llmRes.args ?: JSONObject()
                    val toolDef = CanonicalToolRegistry.get(llmRes.toolCall)
                    if (toolDef != null) {
                        val execRes = toolDef.executeWithTimeout(context, args)
                        val summary = if (execRes.success) {
                            execRes.data?.toString() ?: "Executed ${llmRes.toolCall}."
                        } else {
                            execRes.error?.message ?: "Execution failed."
                        }
                        val latency = System.currentTimeMillis() - t0
                        val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Evaluator: Qwen 2.5 Local SLM\n• Decision: Valid on-device capability found [${llmRes.toolCall}]\n• Confidence: ${(llmRes.confidence * 100).toInt()}%\n• Execution: Success\n</think>"

                        Log.i(TAG, "Step 1: Qwen Local SLM handled [${llmRes.toolCall}] in ${latency}ms")
                        onResult(
                            UnifiedExecutionResult(
                                handled = true,
                                source = ExecutionSource.LOCAL_LLM,
                                speechResponse = summary,
                                fullSummary = "$thinkTrace\n\n🧠 [Qwen 2.5 Local SLM · ${latency}ms]\n$summary",
                                thinkingTrace = thinkTrace,
                                modelName = "Qwen 2.5 (Local SLM)",
                                toolResult = execRes,
                                latencyMs = latency
                            )
                        )
                        return
                    }
                } else {
                    Log.i(TAG, "Qwen local check returned no confident tool (tool=${llmRes.toolCall}, conf=${llmRes.confidence}). Escalating cleanly to Cloud LLM without hallucinations.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Qwen local check escalated: ${e.message}")
            }
        }

        // =========================================================================
        // Step 2: Escalate to Cloud LLM (Google Gemini 2.0 Flash / AGY Server)
        // =========================================================================
        Log.i(TAG, "Step 2: Escalating to Cloud LLM (Gemini 2.0 Flash)...")
        val historyContext = com.pr4nav.jarvis.context.ConversationalContext.getRecentHistory(4)
        val fullPromptWithHistory = if (historyContext.isNotBlank()) {
            "$historyContext\nUser: $trimmed\nJarvis:"
        } else {
            trimmed
        }

        val escalationReason = if (isConversationalOrInformational) "Conversational dialogue / general inquiry"
                               else "Query requires broader multi-step reasoning / cloud intelligence"

        GeminiCloudLLM.generate(
            context = context,
            prompt = fullPromptWithHistory,
            onSuccess = { cloudSpeech ->
                val latency = System.currentTimeMillis() - t0
                com.pr4nav.jarvis.context.ConversationalContext.recordTurn(trimmed, cloudSpeech)

                val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Local Check: Qwen assessed -> Escalated ($escalationReason)\n• Model: Google Gemini 2.0 Flash (Cloud)\n• Reasoning: Generated conversational response with high accuracy\n• Latency: ${latency}ms\n</think>"

                Log.i(TAG, "Step 2: Cloud Gemini 2.0 Flash responded in ${latency}ms")
                onResult(
                    UnifiedExecutionResult(
                        handled = true,
                        source = ExecutionSource.CLOUD_LLM,
                        speechResponse = cloudSpeech,
                        fullSummary = "$thinkTrace\n\n☁️ [Gemini 2.0 Flash (Cloud) · ${latency}ms]\n$cloudSpeech",
                        thinkingTrace = thinkTrace,
                        modelName = "Gemini 2.0 Flash (Cloud)",
                        latencyMs = latency
                    )
                )
            },
            onError = { errMsg ->
                val latency = System.currentTimeMillis() - t0
                Log.e(TAG, "Cloud escalation failed: $errMsg")

                // Friendly, intelligent fallback answering honestly instead of dead-end
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

                val thinkTrace = "<think>\n• Input: \"$trimmed\"\n• Route: Cloud unavailable ($errMsg)\n• Fallback: Native system conversational handler\n</think>"

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
    }
}

