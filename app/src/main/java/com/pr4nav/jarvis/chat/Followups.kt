package com.pr4nav.jarvis.chat

/**
 * Follow-up suggestion chips shown under the latest agent message:
 * contextual follow-up prompts the user can tap to send, plus quick
 * answers when the agent itself asked a question.
 */
object Followups {

    data class Chip(val label: String, val send: String)

    /**
     * @param userPrompt the user's last message
     * @param reply the agent's final answer text
     * @param toolsUsed true when the turn ran tools
     * @param success false when the turn failed
     */
    fun forTurn(
        userPrompt: String,
        reply: String,
        toolsUsed: Boolean,
        success: Boolean
    ): List<Chip> {
        val r = reply.trim()
        if (r.isEmpty()) return emptyList()

        // Agent asked the user a question → offer quick answers first.
        if (r.endsWith("?")) {
            val chips = mutableListOf(
                Chip("Yes", "Yes"),
                Chip("No", "No")
            )
            chips.add(Chip("Options", "What are my options?"))
            return chips.take(3)
        }

        if (!success) {
            return listOf(
                Chip("Try again", "Try a different approach: ${userPrompt.take(120)}"),
                Chip("Explain error", "Explain what went wrong in simple terms"),
                Chip("Details", "Show me the full details")
            )
        }

        if (r.contains("```")) {
            return listOf(
                Chip("Explain code", "Explain that code step by step"),
                Chip("Simplify", "Simplify it"),
                Chip("Example", "Show me an example")
            )
        }

        if (toolsUsed) {
            return listOf(
                Chip("What changed?", "Summarize exactly what you just changed"),
                Chip("Full output", "Show me the full output"),
                Chip("Undo?", "Can you undo that? If yes, do it")
            )
        }

        return listOf(
            Chip("Tell me more", "Tell me more about that"),
            Chip("Example", "Give me an example"),
            Chip("Summarize", "Summarize that in 3 lines")
        )
    }
}
