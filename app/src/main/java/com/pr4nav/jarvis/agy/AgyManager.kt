package com.pr4nav.jarvis.agy

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.Shell
import java.util.Collections

/**
 * AGY Manager (Inert/Disabled per low-battery and Kira AI primary configuration).
 */
object AgyManager {

    private const val TAG = "AgyManager"
    const val DEFAULT_PORT = 5050
    const val DEFAULT_MODEL = "Gemini 3.7 Flash (Low)"

    enum class ServiceState {
        NOT_INSTALLED,
        DISCOVERING,
        INSTALLED,
        DAEMON_STARTING,
        DAEMON_RUNNING,
        PORT_CONFLICT,
        PORT_LISTENING,
        HEALTHY,
        UNHEALTHY,
        BROKEN
    }

    data class SmokeTestResult(
        val passed: Boolean,
        val responseSnippet: String,
        val durationMs: Long,
        val error: String? = null
    )

    data class DiagnosticsReport(
        val state: ServiceState,
        val isBinaryInstalled: Boolean,
        val binaryPath: String,
        val version: String,
        val isPortListening: Boolean,
        val isPortOwnedByAgy: Boolean,
        val isHttpHealthy: Boolean,
        val activePort: Int,
        val activeModel: String,
        val discoveredModels: List<String>,
        val latencyMs: Long
    )

    fun checkStatus(timeoutMs: Long = 10_000L): DiagnosticsReport {
        return DiagnosticsReport(
            state = ServiceState.NOT_INSTALLED,
            isBinaryInstalled = false,
            binaryPath = "disabled",
            version = "disabled",
            isPortListening = false,
            isPortOwnedByAgy = false,
            isHttpHealthy = false,
            activePort = DEFAULT_PORT,
            activeModel = DEFAULT_MODEL,
            discoveredModels = emptyList(),
            latencyMs = 0L
        )
    }

    fun discoverModels(): List<String> = emptyList()

    fun runSmokeTest(timeoutMs: Long = 15_000L): SmokeTestResult {
        return SmokeTestResult(false, "", 0L, "AGY engine is disabled. Kira AI and Groq are active primary models.")
    }

    fun sanitizeModel(requestedModel: String?): String {
        if (requestedModel == null || requestedModel.isBlank()) return DEFAULT_MODEL
        val supported = listOf(
            "Gemini 3.7 Flash (Low)",
            "Gemini 3.7 Flash (Medium)",
            "Gemini 3.7 Flash (High)",
            "Claude Sonnet 4.6 (Thinking)",
            "Claude Haiku 4.5",
            "DeepSeek R1 (Distill)"
        )
        return supported.firstOrNull { it.equals(requestedModel, ignoreCase = true) } ?: DEFAULT_MODEL
    }
}
