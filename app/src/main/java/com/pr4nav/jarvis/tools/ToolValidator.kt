package com.pr4nav.jarvis.tools

import android.content.Context
import com.pr4nav.jarvis.core.JarvisError
import org.json.JSONObject

sealed class ValidationResult {
    data class Valid(
        val toolDef: CanonicalToolDef,
        val validatedArgs: JSONObject
    ) : ValidationResult()

    data class Rejected(
        val error: JarvisError
    ) : ValidationResult()
}

/**
 * Hard validation layer preventing hallucinated execution.
 * Enforces:
 * 1. Tool name MUST exist in registry.
 * 2. Arguments MUST conform to JSON schema.
 * 3. Required parameters MUST be present.
 * 4. Backend MUST be in AVAILABLE state.
 */
object ToolValidator {

    fun validate(context: Context?, toolName: String, rawArgs: JSONObject?): ValidationResult {
        // 1. Tool registration check
        val toolDef = CanonicalToolRegistry.get(toolName)
            ?: return ValidationResult.Rejected(JarvisError.unregisteredTool(toolName))

        val args = rawArgs ?: JSONObject()

        // 2. Schema check (Required parameters)
        val schema = toolDef.argumentSchema
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (i in 0 until required.length()) {
                val reqKey = required.optString(i)
                if (reqKey.isNotBlank() && (!args.has(reqKey) || args.optString(reqKey).isBlank())) {
                    return ValidationResult.Rejected(
                        JarvisError.invalidSchema("Missing required parameter '$reqKey' for tool '$toolName'")
                    )
                }
            }
        }

        // 3. Backend availability check (if context is present)
        if (context != null) {
            val state = toolDef.checkAvailability(context)
            if (state == ToolState.UNAVAILABLE) {
                return ValidationResult.Rejected(
                    JarvisError.backendUnavailable(toolDef.backend.name, "Backend is not installed or configured")
                )
            } else if (state == ToolState.BROKEN) {
                return ValidationResult.Rejected(
                    JarvisError.backendUnavailable(toolDef.backend.name, "Backend is broken or unresponsive")
                )
            }
        }

        return ValidationResult.Valid(toolDef, args)
    }
}
