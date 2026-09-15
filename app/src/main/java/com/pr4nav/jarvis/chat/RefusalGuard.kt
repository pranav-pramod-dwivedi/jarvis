package com.pr4nav.jarvis.chat

/**
 * Detects model cop-outs: claims of INABILITY to act ("I am unable to play…",
 * "go to YouTube yourself") as opposed to honest failure reports
 * ("file not found", "command failed with exit 1").
 * A detected refusal earns exactly one forced tool-attempt retry.
 */
object RefusalGuard {

    private val REFUSAL = listOf(
        Regex("(?i)\\bi\\s*(am|'m|’m)\\s+unable\\s+to\\s+(play|open|launch|control|access|run|do)"),
        Regex("(?i)\\bi\\s*(cannot|can'?t)\\s+(play|open|launch|control|run)\\b"),
        Regex("(?i)\\bunable\\s+to\\s+play\\s+(videos?|music|anything)\\b"),
        Regex("(?i)\\bcann?ot\\s+play\\s+(videos?|music)\\b"),
        Regex("(?i)go\\s+to\\s+youtube\\s+(yourself|and|to)\\b"),
        Regex("(?i)\\byou\\s+can\\s+(go\\s+to|open\\s+it\\s+yourself|do\\s+it\\s+yourself)\\b"),
        Regex("(?i)\\bas\\s+an\\s+(ai|language\\s+model)\\b"),
        Regex("(?i)\\bi\\s+don'?t\\s+have\\s+the\\s+(ability|capability)\\s+to\\s+(play|open|control|access)"),
        Regex("(?i)\\bi\\s+cann?ot\\s+directly\\s+(play|open|control|access|interact)"),
        Regex("(?i)\\bno\\s+direct\\s+access\\s+to\\s+(your|the)\\s+(device|phone)\\b")
    )

    /** True when the text is a refusal to act (not an honest failure report). */
    fun isRefusal(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return REFUSAL.any { it.containsMatchIn(t) }
    }

    /** The one-shot nudge appended as a user message to force tool use. */
    fun nudgePrompt(): String {
        return "Do not refuse. You HAVE working tools on this device: open_app, " +
            "media.play, execute_shell_command, read_file, write_file, execute_device_tool. " +
            "Pick the right tool and complete the task NOW with a tool call. " +
            "Never tell the user to do it themselves."
    }
}
