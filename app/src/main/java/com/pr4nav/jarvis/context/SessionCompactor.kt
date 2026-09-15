package com.pr4nav.jarvis.context

import com.pr4nav.jarvis.session.SessionMessage

/**
 * /compact: condenses long sessions into a summary head + recent tail so
 * turns stay fast and the context limit is never hit. Pure local heuristic —
 * no model call, works offline. Auto-fires when a session grows past AUTO_AT.
 */
object SessionCompactor {

    const val AUTO_AT = 80
    const val KEEP_TAIL = 20
    private const val MAX_TOPICS = 8
    private const val MAX_TOOLS = 10

    fun needsCompact(messages: List<SessionMessage>): Boolean =
        messages.size >= AUTO_AT

    /**
     * Builds a dense summary of the given (older) messages.
     */
    fun summarize(messages: List<SessionMessage>): String {
        if (messages.isEmpty()) return "Empty context."
        val sb = StringBuilder()
        val dropped = messages.size
        sb.append("Compacted context ($dropped older messages condensed):")

        val topics = messages
            .filter { it.sender == "user" }
            .map { it.text.replace(Regex("\\s+"), " ").trim().take(60) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_TOPICS)
        if (topics.isNotEmpty()) {
            sb.append("\nTopics: ")
            sb.append(topics.joinToString(" · ") { "\"$it\"" })
        }

        val tools = messages
            .mapNotNull { it.toolCall?.trim()?.takeIf { t -> t.isNotBlank() } }
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_TOOLS)
        if (tools.isNotEmpty()) {
            sb.append("\nTools used: ")
            sb.append(tools.joinToString(", "))
        }

        val notes = messages
            .filter { it.sender == "system" }
            .map { it.text.replace(Regex("\\s+"), " ").trim().take(80) }
            .filter { it.isNotBlank() }
            .takeLast(6)
        if (notes.isNotEmpty()) {
            sb.append("\nTrail: ")
            sb.append(notes.joinToString(" | "))
        }

        val userCount = messages.count { it.sender == "user" }
        val agentCount = messages.count { it.sender == "agent" }
        sb.append("\nTurns: $userCount user / $agentCount agent.")
        return sb.toString()
    }

    /**
     * Returns [summary head] + last [keepTail] messages. Short histories
     * pass through untouched.
     */
    fun compact(
        messages: List<SessionMessage>,
        keepTail: Int = KEEP_TAIL
    ): List<SessionMessage> {
        if (messages.size <= keepTail + 1) return messages
        val head = SessionMessage(
            sender = "system",
            text = summarize(messages.dropLast(keepTail)),
            isSuccess = true
        )
        return listOf(head) + messages.takeLast(keepTail)
    }
}
