package com.pr4nav.jarvis.chat

/**
 * Thinking is for prose reasoning only. This strips everything that is not
 * text — code fences, JSON blobs, markdown symbols, emoji — so the thinking
 * block renders as clean reading, never a code dump.
 */
object ThinkingSanitizer {

    private val EMOJI = Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}\n]")

    fun sanitize(raw: String, maxChars: Int = 4000): String {
        if (raw.isBlank()) return ""
        var t = raw.trim()

        // Drop fenced code blocks entirely.
        t = t.replace(Regex("```[\\s\\S]*?(?:```|$)"), " ")

        // Drop lines that are JSON / tool envelopes / telemetry.
        val kept = mutableListOf<String>()
        for (line in t.lines()) {
            val s = line.trim()
            if (s.isEmpty()) {
                if (kept.isNotEmpty() && kept.last().isNotEmpty()) kept.add("")
                continue
            }
            if (s.startsWith("{") || s.startsWith("[") || s.startsWith("\"") ||
                s.startsWith("```") ||
                s.matches(Regex("^\"[\\w_]+\":.*")) ||
                s.contains("\"tool_call\"") || s.contains("\"arguments\"") ||
                s.contains("\"function_calls\"") || s.contains("ValidationResult")
            ) continue
            kept.add(s)
        }
        t = kept.joinToString("\n")

        // Strip markdown symbols and emoji, keep sentence punctuation.
        t = t.replace(Regex("[`*#_~>|]"), "")
        t = EMOJI.replace(t, "")
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex("\\n{3,}"), "\n\n").trim()

        if (t.equals("null", ignoreCase = true)) return ""
        return if (t.length > maxChars) t.take(maxChars).trimEnd() + "…" else t
    }

    /** One-line cleaner for status/step rows: no emoji, no markdown symbols. */
    fun cleanLine(raw: String, maxChars: Int = 140): String {
        if (raw.isBlank()) return ""
        var t = raw.trim()
        t = EMOJI.replace(t, "")
        t = t.replace(Regex("[`*#_~>|]"), "")
        t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.equals("null", ignoreCase = true)) return ""
        return if (t.length > maxChars) t.take(maxChars).trimEnd() + "…" else t
    }
}
