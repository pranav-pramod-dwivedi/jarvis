package com.pr4nav.jarvis.router

import android.content.Context
import com.pr4nav.jarvis.llm.DefaultLocalLLM
import com.pr4nav.jarvis.llm.LocalLLM
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class RouterTier {
    DETERMINISTIC_NEEDLE,
    LOCAL_LLM_NEEDLE,
    CLOUD_ESCALATION
}

data class RouterDecision(
    val tier: RouterTier,
    val tool: String? = null,
    val arguments: JSONObject? = null,
    val confidence: Float = 0.0f,
    val reason: String = "",
    val executionResult: ToolResult? = null
)

/**
 * 3-Tier Canonical Router:
 * USER INPUT ->
 *   Tier 1: Deterministic normalization / Needle execution (<1ms - 15ms)
 *   Tier 2: Local LLM interpretation -> Needle execution (<30s)
 *   Tier 3: Complex / uncertain / low confidence (<0.6) -> Cloud escalation
 */
class CanonicalRouter(
    private val localLLM: LocalLLM = DefaultLocalLLM()
) {

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.60f
        const val LOCAL_LLM_TIMEOUT_MS = 30_000L
    }

    /**
     * Routes and evaluates user request through the 3 tiers.
     */
    fun route(context: Context, prompt: String): RouterDecision {
        val rawTrimmed = prompt.trim()
        if (rawTrimmed.isEmpty()) {
            return RouterDecision(
                tier = RouterTier.DETERMINISTIC_NEEDLE,
                confidence = 0f,
                reason = "Empty input"
            )
        }

        // Contextual pronoun resolution (e.g. "call him", "close this app", "read that file")
        val trimmed = com.pr4nav.jarvis.context.ConversationalContext.resolvePronouns(rawTrimmed)

        // Negative check: informational questions should escalate to conversational/cloud, NOT trigger tools
        if (LanguageNormalizer.isInformational(trimmed)) {
            return RouterDecision(
                tier = RouterTier.CLOUD_ESCALATION,
                confidence = 0.0f,
                reason = "Informational/conceptual query; no action execution required"
            )
        }

        // --- Tier 1: Deterministic Match / Language Normalization ---
        CanonicalToolRegistry.init(context)
        val normalized = LanguageNormalizer.normalize(trimmed)
        if (normalized != null && normalized.confidence >= 0.85f) {
            val execRes = CanonicalToolRegistry.execute(context, normalized.tool, normalized.args)
            com.pr4nav.jarvis.context.ConversationalContext.updateContext(normalized.tool, normalized.args)
            return RouterDecision(
                tier = RouterTier.DETERMINISTIC_NEEDLE,
                tool = normalized.tool,
                arguments = normalized.args,
                confidence = normalized.confidence,
                reason = "Normalized deterministic match: ${normalized.tool}",
                executionResult = execRes
            )
        }

        // --- Tier 2: Local LLM Interpretation ---
        if (localLLM.isAvailable()) {
            try {
                val future = localLLM.generate(trimmed, timeoutMs = LOCAL_LLM_TIMEOUT_MS)
                val llmRes = future.get(LOCAL_LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                if (llmRes.toolCall != null && llmRes.confidence >= CONFIDENCE_THRESHOLD) {
                    val toolDef = CanonicalToolRegistry.get(llmRes.toolCall)
                    if (toolDef != null) {
                        val args = llmRes.args ?: JSONObject()
                        // Validate arguments against tool schema
                        val requiredFields = toolDef.argumentSchema.optJSONArray("required")
                        var validArgs = true
                        if (requiredFields != null) {
                            for (i in 0 until requiredFields.length()) {
                                if (!args.has(requiredFields.optString(i))) {
                                    validArgs = false
                                    break
                                }
                            }
                        }

                        if (validArgs) {
                            val execRes = toolDef.executeWithTimeout(context, args)
                            return RouterDecision(
                                tier = RouterTier.LOCAL_LLM_NEEDLE,
                                tool = llmRes.toolCall,
                                arguments = args,
                                confidence = llmRes.confidence,
                                reason = "Local LLM structured interpretation: ${llmRes.toolCall}",
                                executionResult = execRes
                            )
                        }
                    }
                }
            } catch (e: TimeoutException) {
                // Local LLM exceeded 30s; escalate to cloud
                return RouterDecision(
                    tier = RouterTier.CLOUD_ESCALATION,
                    confidence = 0.0f,
                    reason = "Local LLM timed out (>30s), escalating to Cloud"
                )
            } catch (e: Exception) {
                // Parse or execution error, fallback to cloud
            }
        }

        // --- Tier 3: Cloud Escalation ---
        return RouterDecision(
            tier = RouterTier.CLOUD_ESCALATION,
            confidence = 0.0f,
            reason = "No high-confidence deterministic or local tool match, escalating to Cloud"
        )
    }
}
