package com.pr4nav.jarvis.registry

import android.content.Context

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class BackendType {
    ANDROID_API,
    ANDROID_INTENT,
    FILESYSTEM,
    TERMUX,
    UBUNTU,
    AGY,
    OPENCODE,
    INTERNAL_STORE
}

data class CapabilityExecutionResult(
    val success: Boolean,
    val summary: String,
    val data: Any? = null,
    val error: String? = null,
    val permissionRequired: String? = null,
    val destinationSettings: String? = null,
    val latencyMs: Long = 0L
) {
    companion object {
        fun ok(summary: String, data: Any? = null, latencyMs: Long = 0L) =
            CapabilityExecutionResult(success = true, summary = summary, data = data, latencyMs = latencyMs)

        fun fail(error: String, summary: String = error, latencyMs: Long = 0L) =
            CapabilityExecutionResult(success = false, summary = summary, error = error, latencyMs = latencyMs)

        fun permissionRequired(permission: String, reason: String, destination: String = "android.settings.APPLICATION_DETAILS_SETTINGS") =
            CapabilityExecutionResult(
                success = false,
                summary = "Permission Required: $reason",
                error = "PERMISSION_REQUIRED: $permission",
                permissionRequired = permission,
                destinationSettings = destination
            )

        fun confirmationRequired(action: String, warning: String) =
            CapabilityExecutionResult(
                success = false,
                summary = "Confirmation Required: $warning",
                error = "CONFIRMATION_REQUIRED: $action"
            )
    }
}

data class CapabilityDef(
    val id: String,
    val category: String,
    val name: String,
    val description: String,
    val aliases: List<String> = emptyList(),
    val requiredParams: List<String> = emptyList(),
    val optionalParams: List<String> = emptyList(),
    val risk: RiskLevel = RiskLevel.LOW,
    val requiredPermission: String? = null,
    val backend: BackendType = BackendType.ANDROID_API,
    val hasGui: Boolean = true,
    val requiresConfirmation: Boolean = (risk == RiskLevel.HIGH),
    val execute: (context: Context, params: Map<String, Any?>) -> CapabilityExecutionResult
)
