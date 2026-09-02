package com.pr4nav.jarvis.router

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Diagnostic trace entry capturing the complete routing pipeline lifecycle.
 */
data class RouterDiagnosticTrace(
    val timestamp: Long = System.currentTimeMillis(),
    val input: String,
    val category: TaskCategory,
    val classificationConfidence: Float,
    val routeSelected: String,
    val modelEngine: String,
    val toolRequested: String? = null,
    val toolArguments: String? = null,
    val executionResult: String? = null,
    val finalResponse: String,
    val latencyMs: Long,
    val events: List<ActivityEvent> = emptyList(),
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null
) {
    fun toFormattedInspectorString(): String = buildString {
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        append("[$timeStr] ROUTE DIAGNOSTIC TRACE\n")
        append("========================================\n")
        append("INPUT: \"$input\"\n")
        append("CLASSIFICATION: ${category.name} (confidence: ${String.format(Locale.US, "%.2f", classificationConfidence)})\n")
        append("ROUTE SELECTED: $routeSelected\n")
        append("MODEL / ENGINE: $modelEngine\n")
        if (fallbackUsed) {
            append("FALLBACK: YES ($fallbackReason)\n")
        }
        if (toolRequested != null) {
            append("TOOL REQUEST: $toolRequested(${toolArguments ?: ""})\n")
            append("EXECUTION RESULT: ${executionResult ?: "(none)"}\n")
        }
        append("FINAL RESPONSE: \"$finalResponse\"\n")
        append("TOTAL LATENCY: ${latencyMs}ms\n")
        if (events.isNotEmpty()) {
            append("\nACTIVITY TIMELINE:\n")
            for (ev in events) {
                append("• [${ev.state.name}] ${ev.detail} (+${ev.timestamp - timestamp}ms)\n")
            }
        }
    }
}

/**
 * Thread-safe global diagnostics registry storing execution traces for Developer/Diagnostics mode.
 */
object RouterDiagnostics {

    private const val MAX_TRACES = 50
    private val traces = ConcurrentLinkedDeque<RouterDiagnosticTrace>()

    fun record(trace: RouterDiagnosticTrace) {
        traces.addFirst(trace)
        while (traces.size > MAX_TRACES) {
            traces.removeLast()
        }
    }

    fun getRecentTraces(limit: Int = 20): List<RouterDiagnosticTrace> {
        return traces.take(limit)
    }

    fun clear() {
        traces.clear()
    }

    fun toFullDiagnosticsReport(): String = buildString {
        append("JARVIS ROUTER DIAGNOSTICS LOG\n")
        append("Total Recorded Traces: ${traces.size}\n\n")
        val recent = getRecentTraces(20)
        if (recent.isEmpty()) {
            append("No routing traces recorded yet.\n")
        } else {
            for ((index, t) in recent.withIndex()) {
                append("--- TRACE #${index + 1} ---\n")
                append(t.toFormattedInspectorString())
                append("\n\n")
            }
        }
    }
}
