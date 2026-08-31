package com.pr4nav.jarvis.core

import org.json.JSONObject

enum class ErrorType {
    UNREGISTERED_TOOL,
    INVALID_SCHEMA,
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    CATEGORY_MISMATCH,
    SEMANTIC_MISMATCH,
    CONTEXT_MISSING,
    PERMISSION_DENIED,
    BACKEND_UNAVAILABLE,
    BACKEND_BROKEN,
    TIMEOUT,
    EXECUTION_FAILED,
    VERIFICATION_FAILED,
    NOT_FOUND,
    AMBIGUOUS,
    UNKNOWN
}

/**
 * Structured, typed error representation across all JARVIS layers.
 * Replaces random error strings with actionable diagnostic details.
 */
data class JarvisError(
    val errorType: ErrorType,
    val message: String,
    val backend: String = "SYSTEM",
    val recoverable: Boolean = true,
    val suggestedAction: String = "Check subsystem logs or retry with explicit parameters"
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("success", false)
        put("error_type", errorType.name)
        put("message", message)
        put("backend", backend)
        put("recoverable", recoverable)
        put("suggested_action", suggestedAction)
    }

    companion object {
        fun unregisteredTool(name: String) = JarvisError(
            errorType = ErrorType.UNREGISTERED_TOOL,
            message = "Tool '$name' is not registered in CanonicalToolRegistry",
            backend = "ROUTER",
            recoverable = false,
            suggestedAction = "Select a registered tool from CanonicalToolRegistry schema"
        )

        fun categoryMismatch(tool: String, expectedCategory: String, actualCategory: String) = JarvisError(
            errorType = ErrorType.CATEGORY_MISMATCH,
            message = "Tool '$tool' belongs to category '$actualCategory' but request intent requires '$expectedCategory'",
            backend = "VALIDATOR",
            recoverable = true,
            suggestedAction = "Select a tool matching the intended category or escalate to information engine"
        )

        fun semanticMismatch(tool: String, reason: String) = JarvisError(
            errorType = ErrorType.SEMANTIC_MISMATCH,
            message = "Tool '$tool' rejected by semantic guard: $reason",
            backend = "VALIDATOR",
            recoverable = true,
            suggestedAction = "Do not force informational or non-matching queries into this tool"
        )

        fun invalidSchema(message: String) = JarvisError(
            errorType = ErrorType.INVALID_SCHEMA,
            message = message,
            backend = "VALIDATOR",
            recoverable = true,
            suggestedAction = "Ensure arguments conform to tool parameter JSON schema"
        )

        fun permissionDenied(permission: String) = JarvisError(
            errorType = ErrorType.PERMISSION_DENIED,
            message = "Required Android permission not granted: $permission",
            backend = "ANDROID_OS",
            recoverable = true,
            suggestedAction = "Grant permission via Android Settings or in-app permission prompt"
        )

        fun backendUnavailable(backend: String, reason: String) = JarvisError(
            errorType = ErrorType.BACKEND_UNAVAILABLE,
            message = "Backend '$backend' is unavailable: $reason",
            backend = backend,
            recoverable = true,
            suggestedAction = "Start service or check installation in Diagnostics screen"
        )
    }
}
