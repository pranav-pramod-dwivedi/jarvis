package com.pr4nav.jarvis.tools

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Standardized error representation for all canonical tools.
 */
data class ToolError(
    val code: String,
    val message: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("code", code)
        put("message", message)
    }
}

enum class ToolStatus {
    SUCCESS,
    FAILED,
    PERMISSION_REQUIRED,
    ACTION_REQUIRES_USER,
    NOT_SUPPORTED,
    TIMEOUT,
    NOT_FOUND,
    AMBIGUOUS,
    CLOUD_REQUIRED
}

/**
 * Standardized execution result for all canonical tools.
 * Format: { "success": Boolean, "status": ToolStatus, "data": Any?, "error": { "code": String, "message": String }?, "latencyMs": Long }
 */
data class ToolResult(
    val success: Boolean,
    val status: ToolStatus = if (success) ToolStatus.SUCCESS else ToolStatus.FAILED,
    val data: Any? = null,
    val error: ToolError? = null,
    val latencyMs: Long = 0L
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("success", success)
        put("status", status.name)
        if (data != null) {
            put("data", data)
        } else {
            put("data", JSONObject.NULL)
        }
        if (error != null) {
            put("error", error.toJsonObject())
        } else {
            put("error", JSONObject.NULL)
        }
        put("latencyMs", latencyMs)
    }

    companion object {
        fun ok(data: Any? = null, latencyMs: Long = 0L) =
            ToolResult(success = true, status = ToolStatus.SUCCESS, data = data, latencyMs = latencyMs)

        fun failure(code: String, message: String, latencyMs: Long = 0L) =
            ToolResult(success = false, status = ToolStatus.FAILED, error = ToolError(code, message), latencyMs = latencyMs)

        fun permissionDenied(permission: String, latencyMs: Long = 0L) =
            ToolResult(
                success = false,
                status = ToolStatus.PERMISSION_REQUIRED,
                error = ToolError("PERMISSION_REQUIRED", "Permission required: $permission"),
                latencyMs = latencyMs
            )

        fun requiresUser(action: String, explanation: String, latencyMs: Long = 0L) =
            ToolResult(
                success = false,
                status = ToolStatus.ACTION_REQUIRES_USER,
                data = action,
                error = ToolError("ACTION_REQUIRES_USER", explanation),
                latencyMs = latencyMs
            )

        fun notSupported(feature: String, explanation: String, latencyMs: Long = 0L) =
            ToolResult(
                success = false,
                status = ToolStatus.NOT_SUPPORTED,
                error = ToolError("NOT_SUPPORTED", "$feature not supported: $explanation"),
                latencyMs = latencyMs
            )

        fun notFound(item: String, latencyMs: Long = 0L) =
            ToolResult(
                success = false,
                status = ToolStatus.NOT_FOUND,
                error = ToolError("NOT_FOUND", "Item not found: $item"),
                latencyMs = latencyMs
            )

        fun ambiguous(explanation: String, options: List<String> = emptyList(), latencyMs: Long = 0L) =
            ToolResult(
                success = false,
                status = ToolStatus.AMBIGUOUS,
                data = options,
                error = ToolError("AMBIGUOUS", explanation),
                latencyMs = latencyMs
            )

        fun timeout(timeoutMs: Long) =
            ToolResult(
                success = false,
                status = ToolStatus.TIMEOUT,
                error = ToolError("TIMEOUT", "Tool execution timed out after ${timeoutMs}ms"),
                latencyMs = timeoutMs
            )

        fun invalidArguments(message: String) =
            ToolResult(
                success = false,
                status = ToolStatus.FAILED,
                error = ToolError("INVALID_ARGUMENTS", message)
            )
    }
}

/**
 * Canonical Tool definition.
 * Holds name, description, JSON schema for arguments, required permissions, timeout,
 * and execution function.
 */
data class CanonicalToolDef(
    val name: String,
    val description: String,
    val argumentSchema: JSONObject,
    val requiredPermissions: List<String> = emptyList(),
    val defaultTimeoutMs: Long = 10_000L,
    val execute: (context: Context, args: JSONObject) -> ToolResult
) {
    /**
     * Executes the tool with timeout enforcement.
     */
    fun executeWithTimeout(context: Context, args: JSONObject, timeoutOverrideMs: Long? = null): ToolResult {
        val timeout = timeoutOverrideMs ?: defaultTimeoutMs
        val t0 = System.currentTimeMillis()

        // 1. Argument validation against schema (checks required fields)
        val requiredFields = argumentSchema.optJSONArray("required")
        if (requiredFields != null) {
            for (i in 0 until requiredFields.length()) {
                val field = requiredFields.optString(i)
                if (field.isNotBlank() && !args.has(field)) {
                    return ToolResult.invalidArguments("Missing required argument: $field")
                }
            }
        }

        // 2. Permission check (if context can check permissions)
        for (perm in requiredPermissions) {
            try {
                if (context.checkCallingOrSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return ToolResult.permissionDenied(perm, System.currentTimeMillis() - t0)
                }
            } catch (_: Throwable) {
                // If checking permission throws in unit test environments without Android framework, proceed
            }
        }

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable {
            execute(context, args)
        })

        return try {
            val res = future.get(timeout, TimeUnit.MILLISECONDS)
            executor.shutdownNow()
            val totalLatency = System.currentTimeMillis() - t0
            res.copy(latencyMs = totalLatency)
        } catch (e: TimeoutException) {
            future.cancel(true)
            executor.shutdownNow()
            ToolResult.timeout(timeout)
        } catch (e: Exception) {
            future.cancel(true)
            executor.shutdownNow()
            val latency = System.currentTimeMillis() - t0
            ToolResult.failure("EXECUTION_ERROR", e.message ?: e.javaClass.simpleName, latency)
        }
    }
}
