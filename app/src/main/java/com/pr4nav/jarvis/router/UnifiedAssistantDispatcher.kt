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

enum class ExecutionSource {
    DETERMINISTIC_NEEDLE,
    LOCAL_LLM,
    CLOUD_LLM,
    FALLBACK
}

data class UnifiedExecutionResult(
    val handled: Boolean,
    val source: ExecutionSource,
    val speechResponse: String,
    val fullSummary: String = speechResponse,
    val toolResult: ToolResult? = null,
    val latencyMs: Long = 0L
)

/**
 * Unified Autonomous Assistant Dispatcher.
 * Executes the complete 3-Tier hierarchy:
 *
 * Request
 *   ↓
 * Tier 1: Deterministic Needle & Normalizer (<15ms)
 *   ↓ (if tool matched) -> Execute Canonical Tool -> Spoken result
 *   ↓ (if unhandled or informational/general knowledge)
 * Tier 2: Local SLM (Qwen 2.5 / NeedleRuntime)
 *   ↓ (if tool or local answer found) -> Execute Tool / Return speech
 *   ↓ (if conversational, general knowledge, or low confidence)
 * Tier 3: Cloud LLM (Gemini 2.0 Flash HTTPS API / AGY daemon fallback)
 *   ↓
 * Spoken & visual natural response (Never a dead-end canned error)
 */
object UnifiedAssistantDispatcher {

    private const val TAG = "UnifiedAssistant"

    fun execute(
        context: Context,
        rawQuery: String,
        onResult: (UnifiedExecutionResult) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        // Resolve pronouns ("him", "her", "it", "there", "this app") from active conversational session
        val resolvedQuery = com.pr4nav.jarvis.context.ConversationalContext.resolvePronouns(rawQuery)
        val trimmed = resolvedQuery.trim()

        if (trimmed.isEmpty()) {
            onResult(
                UnifiedExecutionResult(
                    handled = false,
                    source = ExecutionSource.FALLBACK,
                    speechResponse = "Yes? How can I help you?",
                    latencyMs = System.currentTimeMillis() - t0
                )
            )
            return
        }

        // Initialize Canonical Tools
        CanonicalToolRegistry.init(context)

        // Negative check: If explicitly informational/conceptual (e.g. "what is gravity?", "explain quantum computing")
        // skip device tool execution and route straight to Tier 2/3 LLM intelligence!
        val isInformational = LanguageNormalizer.isInformational(trimmed)

        if (!isInformational) {
            // ==========================================
            // Tier 1: Deterministic Canonical & Intent Router (<15ms)
            // ==========================================
            // 1.1 LanguageNormalizer (High confidence rules for Phone, Apps, Maps, Media, Settings)
            val normalized = LanguageNormalizer.normalize(trimmed)
            if (normalized != null && normalized.confidence >= 0.85f) {
                try {
                    val toolRes = CanonicalToolRegistry.execute(context, normalized.tool, normalized.args)
                    com.pr4nav.jarvis.context.ConversationalContext.updateContext(normalized.tool, normalized.args)
                    val summary = if (toolRes.success) {
                        toolRes.data?.toString() ?: "Completed ${normalized.tool}."
                    } else {
                        toolRes.error?.message ?: "Failed to execute ${normalized.tool}."
                    }
                    val latency = System.currentTimeMillis() - t0
                    Log.i(TAG, "Tier 1: Normalized tool match [${normalized.tool}] in ${latency}ms")
                    onResult(
                        UnifiedExecutionResult(
                            handled = true,
                            source = ExecutionSource.DETERMINISTIC_NEEDLE,
                            speechResponse = summary,
                            fullSummary = "⚡ [Tier 1: ${normalized.tool} · ${latency}ms]\n$summary",
                            toolResult = toolRes,
                            latencyMs = latency
                        )
                    )
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Tier 1 execution failed: ${e.message}", e)
                }
            }

            // 1.2 JarvisIntentRouter (Compound intents, multi-capability workflows)
            var intentHandled = false
            val routed = JarvisIntentRouter.routeAndExecute(context, trimmed) { res ->
                intentHandled = true
                val latency = System.currentTimeMillis() - t0
                Log.i(TAG, "Tier 1: JarvisIntentRouter matched in ${latency}ms")
                onResult(
                    UnifiedExecutionResult(
                        handled = true,
                        source = ExecutionSource.DETERMINISTIC_NEEDLE,
                        speechResponse = res.executionSummary,
                        fullSummary = "⚡ [Tier 1: Canonical Intent · ${latency}ms]\n${res.executionSummary}",
                        latencyMs = latency
                    )
                )
            }
            if (routed) return
        }

        // ==========================================
        // Tier 2: Local On-Device SLM (Qwen 2.5 / NeedleRuntime)
        // ==========================================
        val qwen = QwenLocalLLM(context)
        val activeModelId = LocalModelManager.getActiveModelId(context)
        val isLocalModelInstalled = LocalModelManager.isModelInstalled(context, activeModelId)

        if (isLocalModelInstalled && qwen.isAvailable() && !isInformational) {
            try {
                Log.i(TAG, "Tier 2: Querying local SLM ($activeModelId)...")
                val future = qwen.generate(trimmed, timeoutMs = 8_000L)
                val llmRes = future.get(8_000L, TimeUnit.MILLISECONDS)

                if (llmRes.toolCall != null && llmRes.confidence >= 0.65f) {
                    val args = llmRes.args ?: JSONObject()
                    val summary = try {
                        val toolDef = CanonicalToolRegistry.get(llmRes.toolCall)
                        if (toolDef != null) {
                            val execRes = toolDef.executeWithTimeout(context, args)
                            if (execRes.success) {
                                execRes.data?.toString() ?: "Executed ${llmRes.toolCall}."
                            } else {
                                execRes.error?.message ?: "Execution failed."
                            }
                        } else {
                            val map = mutableMapOf<String, Any?>()
                            val keys = args.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                map[k] = args.opt(k)
                            }
                            val routeRes = com.pr4nav.jarvis.needle.NeedleRouteResult(
                                route = com.pr4nav.jarvis.needle.RouteType.DIRECT_TOOL,
                                tool = llmRes.toolCall,
                                arguments = map,
                                confidence = llmRes.confidence.toDouble(),
                                reasoning = "Selected by on-device local SLM"
                            )
                            com.pr4nav.jarvis.needle.NeedleExecutor.execute(context, routeRes)
                        }
                    } catch (e: Exception) {
                        null
                    }

                    if (summary != null) {
                        val latency = System.currentTimeMillis() - t0
                        Log.i(TAG, "Tier 2: Local SLM tool match [${llmRes.toolCall}] in ${latency}ms")
                        onResult(
                            UnifiedExecutionResult(
                                handled = true,
                                source = ExecutionSource.LOCAL_LLM,
                                speechResponse = summary,
                                fullSummary = "🧠 [Tier 2: Local SLM ${llmRes.toolCall} · ${latency}ms]\n$summary",
                                latencyMs = latency
                            )
                        )
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 2: Local SLM failed or timed out: ${e.message}, escalating to Cloud...")
            }
        }

        // ==========================================
        // Tier 3: Cloud LLM Reasoning (Gemini API / AGY Server)
        // ==========================================
        Log.i(TAG, "Tier 3: Escalating query \"$trimmed\" to Cloud LLM...")
        val historyContext = com.pr4nav.jarvis.context.ConversationalContext.getRecentHistory(4)
        val fullPromptWithHistory = if (historyContext.isNotBlank()) {
            "$historyContext\nUser: $trimmed\nJarvis:"
        } else {
            trimmed
        }

        GeminiCloudLLM.generate(
            context = context,
            prompt = fullPromptWithHistory,
            onSuccess = { cloudSpeech ->
                val latency = System.currentTimeMillis() - t0
                Log.i(TAG, "Tier 3: Cloud response received in ${latency}ms")
                com.pr4nav.jarvis.context.ConversationalContext.recordTurn(trimmed, cloudSpeech)
                onResult(
                    UnifiedExecutionResult(
                        handled = true,
                        source = ExecutionSource.CLOUD_LLM,
                        speechResponse = cloudSpeech,
                        fullSummary = "☁️ [Tier 3: Cloud Intelligence · ${latency}ms]\n$cloudSpeech",
                        latencyMs = latency
                    )
                )
            },
            onError = { errMsg ->
                val latency = System.currentTimeMillis() - t0
                Log.e(TAG, "Tier 3 Cloud escalation failed: $errMsg")

                // Friendly, intelligent fallback answering honestly instead of dead-end
                val helpfulFallback = when {
                    trimmed.lowercase().contains("who are you") || trimmed.lowercase().contains("what is your name") ->
                        "I am JARVIS, your personal on-device assistant and companion."
                    trimmed.lowercase().contains("how are you") ->
                        "All systems are operating at peak efficiency, sir. How can I assist you today?"
                    trimmed.lowercase().contains("time") ->
                        "The current time is ${java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())}."
                    trimmed.lowercase().contains("date") ->
                        "Today is ${java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date())}."
                    else ->
                        "I'm having trouble connecting to autonomous reasoning right now. You can ask me to control device features, open apps, make calls, or navigate offline."
                }

                onResult(
                    UnifiedExecutionResult(
                        handled = false,
                        source = ExecutionSource.FALLBACK,
                        speechResponse = helpfulFallback,
                        fullSummary = "⚠️ [Cloud Unreachable · ${latency}ms]\n$helpfulFallback\nError: $errMsg",
                        latencyMs = latency
                    )
                )
            }
        )
    }
}
