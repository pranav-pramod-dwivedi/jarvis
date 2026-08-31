package com.pr4nav.jarvis.tools

import android.content.Context
import com.pr4nav.jarvis.core.JarvisError
import com.pr4nav.jarvis.intent.IntentCategory
import com.pr4nav.jarvis.intent.IntentClassifier
import org.json.JSONObject

sealed class ValidationResult {
    data class Valid(
        val toolDef: CanonicalToolDef,
        val validatedArgs: JSONObject,
        val score: Int = 100
    ) : ValidationResult()

    data class Rejected(
        val error: JarvisError,
        val reasonCode: String = error.errorType.name
    ) : ValidationResult()
}

/**
 * Multi-Stage Hard Validation & Semantic Guard Layer.
 * Enforces:
 * 1. Tool name MUST exist in registry.
 * 2. Schema check (Required parameters present and non-blank).
 * 3. Semantic contract & guard (media.play rejected on "Who is Narendra Modi?").
 * 4. Intent category compatibility.
 * 5. Backend availability check.
 */
object ToolValidator {

    fun validate(
        context: Context?,
        toolName: String,
        rawArgs: JSONObject?,
        prompt: String = ""
    ): ValidationResult {
        // 1. Tool registration check
        val toolDef = CanonicalToolRegistry.get(toolName)
            ?: return ValidationResult.Rejected(JarvisError.unregisteredTool(toolName), "UNREGISTERED_TOOL")

        val args = rawArgs ?: JSONObject()

        // 2. Schema check (Required parameters)
        val schema = toolDef.argumentSchema
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (i in 0 until required.length()) {
                val reqKey = required.optString(i)
                if (reqKey.isNotBlank() && (!args.has(reqKey) || args.optString(reqKey).isBlank())) {
                    return ValidationResult.Rejected(
                        JarvisError.invalidSchema("Missing required parameter '$reqKey' for tool '$toolName'"),
                        "MISSING_PARAMETER"
                    )
                }
            }
        }

        // 3. Semantic contract & guard check (if prompt is provided)
        if (prompt.isNotBlank()) {
            val guardRes = ToolSemanticContracts.checkContract(toolName, prompt, args)
            if (guardRes is SemanticCheckResult.Rejected) {
                return ValidationResult.Rejected(
                    JarvisError.semanticMismatch(toolName, guardRes.message),
                    guardRes.reasonCode
                )
            }

            val scoreBreakdown = ToolRoutingScore.scoreCandidate(toolName, prompt, args, schemaValid = true)
            if (!scoreBreakdown.isAccepted) {
                val reason = scoreBreakdown.rejectionReason ?: "Failed routing score threshold"
                return ValidationResult.Rejected(
                    JarvisError.semanticMismatch(toolName, reason),
                    "SEMANTIC_MISMATCH"
                )
            }
        }

        // 4. Backend availability check (if context is present)
        if (context != null) {
            val state = toolDef.checkAvailability(context)
            if (state == ToolState.UNAVAILABLE) {
                return ValidationResult.Rejected(
                    JarvisError.backendUnavailable(toolDef.backend.name, "Backend is not installed or configured"),
                    "BACKEND_UNAVAILABLE"
                )
            } else if (state == ToolState.BROKEN) {
                return ValidationResult.Rejected(
                    JarvisError.backendUnavailable(toolDef.backend.name, "Backend is broken or unresponsive"),
                    "BACKEND_BROKEN"
                )
            }
        }

        return ValidationResult.Valid(toolDef, args)
    }
}
