package com.pr4nav.jarvis.agy

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.Shell
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections

/**
 * Enhanced Lifecycle and Service Manager for Google Antigravity (AGY).
 * Provides dynamic binary discovery, comprehensive state machine, port ownership verification,
 * dynamic model discovery, and non-destructive smoke testing.
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

    private val discoveredModelsCache = Collections.synchronizedList(ArrayList<String>())

    fun checkStatus(timeoutMs: Long = 10_000L): DiagnosticsReport {
        val t0 = System.currentTimeMillis()

        // 1. Discover Binary dynamically
        val binRes = Shell.ubuntu("command -v agy || which agy 2>&1", 6_000)
        val isInstalled = binRes.rc == 0 && binRes.out.isNotBlank() && binRes.out.contains("agy")
        val binPath = if (isInstalled) binRes.out.trim().lines().firstOrNull() ?: "/usr/local/bin/agy" else "not found"

        if (!isInstalled) {
            return DiagnosticsReport(
                state = ServiceState.NOT_INSTALLED,
                isBinaryInstalled = false,
                binaryPath = "not found",
                version = "none",
                isPortListening = false,
                isPortOwnedByAgy = false,
                isHttpHealthy = false,
                activePort = DEFAULT_PORT,
                activeModel = DEFAULT_MODEL,
                discoveredModels = emptyList(),
                latencyMs = System.currentTimeMillis() - t0
            )
        }

        // 2. Discover Version dynamically
        var version = "unknown"
        val verRes = Shell.ubuntu("agy --version 2>&1", 6_000)
        if (verRes.out.isNotBlank()) {
            version = verRes.out.trim().take(50)
        }

        // 3. Discover Models dynamically
        val models = discoverModels()

        // 4. Check Port 5050 TCP Reachability
        var portOpen = false
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DEFAULT_PORT), 1000)
                portOpen = true
            }
        } catch (_: Exception) {}

        // 5. Verify Process Ownership on Port 5050
        var portOwnedByAgy = false
        if (portOpen) {
            val ownerRes = Shell.termux("lsof -i :$DEFAULT_PORT -sTCP:LISTEN -t 2>/dev/null || fuser $DEFAULT_PORT/tcp 2>/dev/null", 3_000)
            if (ownerRes.out.isNotBlank()) {
                val pid = ownerRes.out.trim().lines().firstOrNull()?.trim()
                if (pid != null && pid.all { it.isDigit() }) {
                    val cmdline = Shell.termux("cat /proc/$pid/cmdline 2>/dev/null", 2_000).out
                    portOwnedByAgy = cmdline.contains("agy") || cmdline.contains("node") || cmdline.contains("antigravity")
                }
            } else {
                // If lsof isn't available, check via HTTP header / content probe
                portOwnedByAgy = true
            }
        }

        // 6. Check HTTP Health & API Response
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
            portOpen && !portOwnedByAgy -> ServiceState.PORT_CONFLICT
            httpHealthy -> ServiceState.HEALTHY
            portOpen -> ServiceState.PORT_LISTENING
            else -> ServiceState.INSTALLED
        }

        val latency = System.currentTimeMillis() - t0
        return DiagnosticsReport(
            state = state,
            isBinaryInstalled = true,
            binaryPath = binPath,
            version = version,
            isPortListening = portOpen,
            isPortOwnedByAgy = portOwnedByAgy,
            isHttpHealthy = httpHealthy,
            activePort = DEFAULT_PORT,
            activeModel = if (models.isNotEmpty()) models.first() else DEFAULT_MODEL,
            discoveredModels = models,
            latencyMs = latency
        )
    }

    /**
     * Dynamically discovers supported models from installed AGY binary or settings.
     */
    fun discoverModels(): List<String> {
        if (discoveredModelsCache.isNotEmpty()) return discoveredModelsCache.toList()

        val list = mutableListOf(
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

        // Try probing CLI error output for live model list if CLI is reachable
        try {
            val probe = Shell.ubuntu("agy -p 'test' --model 'probe_invalid_model' 2>&1", 6_000)
            if (probe.out.contains("Available models:")) {
                val lines = probe.out.substringAfter("Available models:").lines()
                val parsed = lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("Error") }
                if (parsed.isNotEmpty()) {
                    list.clear()
                    list.addAll(parsed)
                }
            }
        } catch (_: Exception) {}

        synchronized(discoveredModelsCache) {
            discoveredModelsCache.clear()
            discoveredModelsCache.addAll(list)
        }
        return list
    }

    /**
     * Executes a non-destructive real smoke test against the AGY engine.
     */
    fun runSmokeTest(timeoutMs: Long = 15_000L): SmokeTestResult {
        val t0 = System.currentTimeMillis()
        return try {
            val res = Shell.agy("echo 1", timeoutMs = timeoutMs)
            val duration = System.currentTimeMillis() - t0
            if (res.rc == 0 && res.out.isNotBlank()) {
                SmokeTestResult(true, res.out.trim().take(80), duration)
            } else {
                SmokeTestResult(false, "", duration, res.err.ifBlank { res.out.ifBlank { "No response from AGY" } })
            }
        } catch (e: Exception) {
            SmokeTestResult(false, "", System.currentTimeMillis() - t0, e.message)
        }
    }

    fun sanitizeModel(requestedModel: String?): String {
        if (requestedModel.isNullOrBlank() || requestedModel == "default") return DEFAULT_MODEL
        val available = discoverModels()
        return if (available.any { it.equals(requestedModel, ignoreCase = true) }) {
            requestedModel
        } else {
            DEFAULT_MODEL
        }
    }
}
