package com.pr4nav.jarvis.needle

import android.content.Context
import java.util.Locale

/**
 * Main Needle 2 Local Router.
 * Ultra-low-latency local intent router deciding whether to execute deterministic tools,
 * render GUI dashboards, ask for clarification, or escalate to conversational LLM.
 */
object NeedleRouter {

    /**
     * Routes natural language input to a structured RouteResult.
     */
    fun route(input: String, context: Context? = null, forceGui: Boolean = false): NeedleRouteResult {
        val timing = TimingMetrics(inputReceivedMs = System.currentTimeMillis())
        val trimmed = input.trim()
        val lower = trimmed.lowercase(Locale.US)

        if (trimmed.isEmpty()) {
            return NeedleRouteResult(RouteType.ESCALATE, null, emptyMap(), 0.0, "Empty input", timing = timing)
        }

        // Detect GUI presentation intent (e.g. "Show my battery" vs "What's my battery")
        val isGuiIntent = forceGui || lower.startsWith("show ") || lower.contains("graph of") ||
                lower.contains("dashboard") || lower.contains("visualize")

        timing.needleStartMs = System.currentTimeMillis()
        val envelope = NeedleRuntime.complete(trimmed)
        timing.needleEndMs = System.currentTimeMillis()

        val calls = envelope.functionCalls
        if (calls.isEmpty() || envelope.confidence < NeedleConfig.mediumConfidenceThreshold) {
            // Low confidence or unsupported request -> escalate to LLM
            NeedleRuntime.llmEscalations.incrementAndGet()
            return NeedleRouteResult(
                route = RouteType.ESCALATE,
                tool = null,
                arguments = emptyMap(),
                confidence = envelope.confidence,
                reasoning = envelope.reasoning ?: "Escalated to LLM reasoning model.",
                timing = timing,
                envelope = envelope
            )
        }

        val firstCall = calls.first()
        val toolName = firstCall.name
        val risk = NeedleConfig.riskLevel(toolName)

        // Stricter handling for high-risk operations (destructive filesystem, external messaging)
        if (risk == RiskLevel.HIGH && envelope.confidence < NeedleConfig.destructiveThreshold) {
            return NeedleRouteResult(
                route = RouteType.CLARIFICATION,
                tool = toolName,
                arguments = firstCall.arguments,
                confidence = envelope.confidence,
                reasoning = "High-impact operation requires confirmation.",
                timing = timing,
                envelope = envelope
            )
        }

        // Ambiguous confidence -> request clarification
        if (envelope.confidence < NeedleConfig.highConfidenceThreshold) {
            return NeedleRouteResult(
                route = RouteType.CLARIFICATION,
                tool = toolName,
                arguments = firstCall.arguments,
                confidence = envelope.confidence,
                reasoning = "Ambiguous request, requesting user clarification.",
                timing = timing,
                envelope = envelope
            )
        }

        // GUI-aware routing
        val finalRoute = if (isGuiIntent || toolName == "gui.show_dashboard") {
            RouteType.GUI
        } else {
            RouteType.DIRECT_TOOL
        }

        return NeedleRouteResult(
            route = finalRoute,
            tool = toolName,
            arguments = firstCall.arguments,
            confidence = envelope.confidence,
            reasoning = envelope.reasoning,
            timing = timing,
            envelope = envelope
        )
    }
}
