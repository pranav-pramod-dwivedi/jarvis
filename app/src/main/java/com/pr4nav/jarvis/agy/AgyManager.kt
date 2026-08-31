package com.pr4nav.jarvis.agy

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.Shell
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Lifecycle and Service Manager for Google Antigravity (AGY).
 * Provides reliable binary discovery, daemon health monitoring, and model validation.
 */
object AgyManager {

    private const val TAG = "AgyManager"
    const val DEFAULT_PORT = 5050
    const val DEFAULT_MODEL = "Gemini 3.7 Flash (Low)"

    val AVAILABLE_MODELS = listOf(
        "Gemini 3.7 Flash (Low)",
        "Gemini 3.7 Flash (Medium)",
        "Gemini 3.7 Flash (High)",
        "Gemini 3.6 Flash (Low)",
        "Gemini 3.6 Flash (Medium)",
        "Gemini 3.6 Flash (High)",
        "Gemini 3.1 Pro (Low)",
        "Gemini 3.1 Pro (High)",
        "Claude Sonnet 4.6 (Thinking)",
        "Claude Opus 4.6 (Thinking)",
        "GPT-OSS 120B (Medium)"
    )

    enum class ServiceState {
        NOT_INSTALLED,
        BINARY_FOUND_DAEMON_STOPPED,
        PORT_OPEN_BUT_UNHEALTHY,
        DAEMON_RUNNING_READY
    }

    data class DiagnosticsReport(
        val state: ServiceState,
        val isBinaryInstalled: Boolean,
        val binaryPath: String,
        val version: String,
        val isPortListening: Boolean,
        val isHttpHealthy: Boolean,
        val activePort: Int,
        val activeModel: String,
        val latencyMs: Long
    )

    fun checkStatus(timeoutMs: Long = 10_000L): DiagnosticsReport {
        val t0 = System.currentTimeMillis()

        // 1. Discover Binary
        val binRes = Shell.ubuntu("command -v agy || which agy 2>&1", 8_000)
        val isInstalled = binRes.rc == 0 && binRes.out.isNotBlank() && binRes.out.contains("agy")
        val binPath = if (isInstalled) binRes.out.trim().lines().firstOrNull() ?: "/usr/local/bin/agy" else "not found"

        // 2. Query Version
        var version = "unknown"
        if (isInstalled) {
            val verRes = Shell.ubuntu("agy --version 2>&1", 8_000)
            if (verRes.out.isNotBlank()) {
                version = verRes.out.trim().take(40)
            }
        }

        // 3. Check Port 5050 TCP reachability
        var portOpen = false
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DEFAULT_PORT), 1500)
                portOpen = true
            }
        } catch (_: Exception) {}

        // 4. Check HTTP Health
        var httpHealthy = false
        if (portOpen) {
            try {
                val url = URL("http://127.0.0.1:$DEFAULT_PORT/")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                httpHealthy = code in 200..403
                conn.disconnect()
            } catch (_: Exception) {}
        }

        val state = when {
            !isInstalled -> ServiceState.NOT_INSTALLED
            httpHealthy -> ServiceState.DAEMON_RUNNING_READY
            portOpen -> ServiceState.PORT_OPEN_BUT_UNHEALTHY
            else -> ServiceState.BINARY_FOUND_DAEMON_STOPPED
        }

        val latency = System.currentTimeMillis() - t0
        return DiagnosticsReport(
            state = state,
            isBinaryInstalled = isInstalled,
            binaryPath = binPath,
            version = version,
            isPortListening = portOpen,
            isHttpHealthy = httpHealthy,
            activePort = DEFAULT_PORT,
            activeModel = DEFAULT_MODEL,
            latencyMs = latency
        )
    }

    /**
     * Sanitizes a selected model to ensure it is known to the AGY CLI.
     */
    fun sanitizeModel(requestedModel: String?): String {
        if (requestedModel.isNullOrBlank() || requestedModel == "default") return DEFAULT_MODEL
        return if (AVAILABLE_MODELS.any { it.equals(requestedModel, ignoreCase = true) }) {
            requestedModel
        } else {
            DEFAULT_MODEL
        }
    }
}
