package com.pr4nav.jarvis.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.needle.NeedleDiagnostics
import com.pr4nav.jarvis.needle.NeedleExecutor
import com.pr4nav.jarvis.needle.NeedleRouter
import com.pr4nav.jarvis.needle.RouteType
import com.pr4nav.jarvis.router.JarvisIntentRouter
import kotlin.concurrent.thread

enum class InputSource {
    TEXT,
    VOICE,
    BUTTON,
    INTENT
}

data class OrchestratorResult(
    val layer: String,
    val summary: String,
    val fullOutput: String = summary,
    val latencyMs: Long = 0L,
    val isStreaming: Boolean = false,
    val isError: Boolean = false
)

/**
 * Unified JARVIS Orchestrator.
 * Normalizes all inputs (Voice, Text, Buttons, Intents) into one single execution pipeline:
 *
 * USER
 *   ↓
 * INPUT
 *   ↓
 * NEEDLE 2 REFLEX (<15ms)
 *   ↓
 * LOCAL TOOL / MEMORY / INTENT
 *   ↓
 * GUI / APP
 *   ↓
 * TERMUX (Shell / Linux)
 *   ↓
 * AGY CLOUD LLM (Default autonomous reasoning on :5050 / PRoot)
 *   ↓
 * RESULT
 */
object JarvisOrchestrator {

    private const val TAG = "JarvisOrchestrator"
    private const val DEFAULT_AGY_MODEL = "Gemini 3.7 Flash (Low)"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun processRequest(
        context: Context,
        prompt: String,
        source: InputSource = InputSource.TEXT,
        onChunk: ((String) -> Unit)? = null,
        onComplete: (OrchestratorResult) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        val trimmed = prompt.trim()

        if (trimmed.isEmpty()) {
            onComplete(OrchestratorResult("Input", "Empty request received.", isError = true))
            return
        }

        // 1. Direct Shell Execution Prefix (! or $)
        if (trimmed.startsWith("!") || trimmed.startsWith("$")) {
            val cmd = trimmed.removePrefix("!").removePrefix("$").trim()
            thread(name = "Jarvis-Shell-Thread") {
                val res = Shell.termux(cmd, 30_000)
                val totalMs = System.currentTimeMillis() - t0
                val out = if (res.out.isNotBlank()) res.out.trim() else res.err.trim()
                mainHandler.post {
                    onComplete(
                        OrchestratorResult(
                            layer = "Termux Shell",
                            summary = out.take(300),
                            fullOutput = out,
                            latencyMs = totalMs,
                            isError = res.rc != 0
                        )
                    )
                }
            }
            return
        }

        // 2. Developer Diagnostic Command Shortcuts
        val lower = trimmed.lowercase()
        if (lower == "needle status" || lower == "needle diag" || lower == "needle") {
            val rep = NeedleDiagnostics.getReport(context).formatReport()
            val totalMs = System.currentTimeMillis() - t0
            onComplete(
                OrchestratorResult(
                    layer = "Needle 2 Diagnostics",
                    summary = rep,
                    latencyMs = totalMs
                )
            )
            return
        }

        // 2.3 Tier 1 Canonical Language Normalizer (English / Hindi / Hinglish -> Canonical Tool)
        com.pr4nav.jarvis.tools.CanonicalToolRegistry.init(context)
        val normalized = com.pr4nav.jarvis.router.LanguageNormalizer.normalize(trimmed)
        if (normalized != null && normalized.confidence >= 0.90f) {
            val toolRes = com.pr4nav.jarvis.tools.CanonicalToolRegistry.execute(context, normalized.tool, normalized.args)
            val totalMs = System.currentTimeMillis() - t0
            val summaryText = if (toolRes.success) {
                toolRes.data?.toString() ?: "Executed ${normalized.tool} successfully."
            } else {
                toolRes.error?.message ?: "Execution failed."
            }
            val badge = "⚡ [Canonical Tool: ${normalized.tool} · ${totalMs}ms]\n🎯 $summaryText"
            onComplete(
                OrchestratorResult(
                    layer = "Language Normalizer -> Canonical Tool",
                    summary = summaryText,
                    fullOutput = badge,
                    latencyMs = totalMs,
                    isError = !toolRes.success
                )
            )
            return
        }

        // 2.5 Deterministic Capability Registry Match (<1ms fast path)
        val directMatch = com.pr4nav.jarvis.registry.CapabilityRegistry.match(trimmed)
        if (directMatch != null && directMatch.confidence >= 0.90) {
            val execRes = com.pr4nav.jarvis.registry.CapabilityRegistry.execute(directMatch.capability.id, directMatch.params, context)
            val totalMs = System.currentTimeMillis() - t0
            val badge = "⚡ [Capability: ${directMatch.capability.id} · ${totalMs}ms]\n🎯 ${execRes.summary}"
            onComplete(
                OrchestratorResult(
                    layer = "Capability Registry",
                    summary = execRes.summary,
                    fullOutput = badge,
                    latencyMs = totalMs,
                    isError = !execRes.success
                )
            )
            return
        }

        // 2.7 Compound Command Handling (Level 3 & 4 Workflows)
        if (com.pr4nav.jarvis.registry.JarvisWorkflowEngine.isCompound(trimmed)) {
            val compoundRes = com.pr4nav.jarvis.registry.JarvisWorkflowEngine.executeCompound(context, trimmed)
            val totalMs = System.currentTimeMillis() - t0
            val badge = "⚡ [Workflow: ${compoundRes.steps.size} steps · ${totalMs}ms]\n${compoundRes.aggregatedSummary}"
            onComplete(
                OrchestratorResult(
                    layer = "Workflow Engine",
                    summary = compoundRes.aggregatedSummary,
                    fullOutput = badge,
                    latencyMs = totalMs,
                    isError = !compoundRes.success
                )
            )
            return
        }

        // 3. Layer 1: Needle 2 Reflex Layer (<15ms)
        val needleResult = NeedleRouter.route(trimmed, context)
        if (needleResult.route == RouteType.DIRECT_TOOL || needleResult.route == RouteType.GUI) {
            val summary = NeedleExecutor.execute(context, needleResult)
            val timing = needleResult.timing
            val badge = "⚡ [Needle 2: ${timing.needleLatencyMs}ms · Tool: ${timing.toolLatencyMs}ms · Total: ${timing.totalLatencyMs}ms]\n🎯 $summary"
            onComplete(
                OrchestratorResult(
                    layer = if (needleResult.route == RouteType.GUI) "JARVIS GUI" else "Needle 2 Reflex",
                    summary = summary,
                    fullOutput = badge,
                    latencyMs = timing.totalLatencyMs
                )
            )
            return
        } else if (needleResult.route == RouteType.CLARIFICATION) {
            val clar = "🤔 [Needle 2]: ${needleResult.reasoning ?: "Did you mean to run ${needleResult.tool}?"}"
            onComplete(
                OrchestratorResult(
                    layer = "Needle 2 Reflex (Clarification)",
                    summary = clar,
                    latencyMs = needleResult.timing.totalLatencyMs
                )
            )
            return
        }

        // 4. Layer 2: Local Intent & Memory Router (Secondary Fallback)
        var intentHandled = false
        val routed = JarvisIntentRouter.routeAndExecute(context, trimmed) { res ->
            intentHandled = true
            val totalMs = System.currentTimeMillis() - t0
            onComplete(
                OrchestratorResult(
                    layer = "Local Capability",
                    summary = res.executionSummary,
                    fullOutput = "🎯 ${res.executionSummary}",
                    latencyMs = totalMs
                )
            )
        }
        if (routed) return

        // 5. Layer 3: AGY as Default Cloud/Reasoning LLM (Escalation Target)
        // Dispatches to AGY inside PRoot Ubuntu with streamed response
        thread(name = "Jarvis-Agy-Escalation") {
            try {
                val escaped = trimmed.replace("\"", "\\\"").replace("$", "\\$")
                val cmd = "agy -p \"$escaped\" --continue --dangerously-skip-permissions --model \"$DEFAULT_AGY_MODEL\" 2>&1"
                val res = Shell.ubuntu(cmd, timeoutMs = 180_000)
                val totalMs = System.currentTimeMillis() - t0

                val out = if (res.out.isNotBlank()) res.out.trim() else res.err.trim()
                mainHandler.post {
                    if (res.out.isNotBlank()) {
                        // Stream output in simulated word chunks for smooth reading
                        streamChunks(out, onChunk) {
                            onComplete(
                                OrchestratorResult(
                                    layer = "AGY Agent ($DEFAULT_AGY_MODEL)",
                                    summary = out.take(300),
                                    fullOutput = out,
                                    latencyMs = totalMs,
                                    isStreaming = true
                                )
                            )
                        }
                    } else {
                        onComplete(
                            OrchestratorResult(
                                layer = "AGY Agent",
                                summary = out.ifBlank { "No response received from AGY daemon." },
                                fullOutput = out,
                                latencyMs = totalMs,
                                isError = true
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AGY escalation failed: ${e.message}", e)
                val totalMs = System.currentTimeMillis() - t0
                mainHandler.post {
                    onComplete(
                        OrchestratorResult(
                            layer = "AGY Agent",
                            summary = "Reasoning error: ${e.message}",
                            latencyMs = totalMs,
                            isError = true
                        )
                    )
                }
            }
        }
    }

    private fun streamChunks(text: String, onChunk: ((String) -> Unit)?, onDone: () -> Unit) {
        if (onChunk == null) {
            onDone()
            return
        }
        val words = text.split(" ")
        if (words.size <= 4) {
            onChunk(text)
            onDone()
            return
        }

        var idx = 0
        val chunkSize = 3
        val runnable = object : Runnable {
            override fun run() {
                if (idx < words.size) {
                    val end = minOf(idx + chunkSize, words.size)
                    val chunk = words.subList(idx, end).joinToString(" ") + if (end < words.size) " " else ""
                    onChunk(chunk)
                    idx = end
                    mainHandler.postDelayed(this, 25)
                } else {
                    onDone()
                }
            }
        }
        mainHandler.post(runnable)
    }
}
