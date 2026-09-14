package com.pr4nav.jarvis.chat

/**
 * Structured stream of agent activity. Rendered by the chat UI as
 * thinking blocks, tool cards, message bubbles and artifact cards —
 * never as raw dumped text.
 */
sealed class AgentStreamEvent {

    /** Transient status line ("Querying Kira AI (glm-5.3-free)…"). */
    data class Status(val text: String) : AgentStreamEvent()

    /** Fragment of the model's internal reasoning (prose only after sanitizing). */
    data class ThinkingDelta(val text: String) : AgentStreamEvent()

    /** Reasoning finished; UI collapses the thinking block with duration. */
    data class ThinkingDone(val durationMs: Long) : AgentStreamEvent()

    /** A tool invocation started. */
    data class ToolStart(
        val tool: String,
        val label: String,
        val detail: String
    ) : AgentStreamEvent()

    /** A tool invocation finished. actionId carries e.g. a browser app id for "Open". */
    data class ToolEnd(
        val tool: String,
        val label: String,
        val detail: String,
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val verified: Boolean,
        val actionId: String? = null
    ) : AgentStreamEvent()

    /** Interim/final prose while streaming. */
    data class TextDelta(val text: String) : AgentStreamEvent()

    /** An artifact or mini-app was materialized and is openable. */
    data class ArtifactSaved(
        val id: String,
        val title: String,
        val type: String,
        val filePath: String
    ) : AgentStreamEvent()

    /** Turn finished. UI chunks this into message bubbles. */
    data class Final(
        val text: String,
        val model: String = "",
        val latencyMs: Long = 0L,
        val handled: Boolean = true,
        val thinkingTrace: String = "",
        val toolsUsed: Int = 0
    ) : AgentStreamEvent()

    data class Error(val message: String) : AgentStreamEvent()
}
