package com.pr4nav.jarvis.response

import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

/**
 * Hard User-Output Guard.
 * Final safety net inspecting every user-facing response before UI or TTS consumption.
 * Strictly guarantees that internal model JSON, function envelopes, validation metrics,
 * and debugging traces NEVER reach the user-facing chat bubble, voice output, or notifications.
 */
object UserResponseSanitizer {

    /**
     * Checks if a string appears to be raw JSON, a tool envelope, or internal model telemetry.
     */
    fun isRawJsonOrInternalStructure(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false

        // Direct JSON object or array delimiters
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return true
        }

        // Internal JSON keys / structure tokens
        val internalMarkers = listOf(
            "\"type\":", "\"intent\":", "\"category\":", "\"confidence\":",
            "\"function_calls\":", "\"direct_answer\":", "\"arguments\":",
            "\"tool_call\":", "\"toolCall\":", "\"rawJson\":", "\"model_loaded\":",
            "\"identity_test\":", "ValidationResult.", "Score: 100/100"
        )
        return internalMarkers.any { trimmed.contains(it) }
    }

    private val IDENTITY_LEAK_PATTERNS = listOf(
        Regex("(?i)\\b(?:i am|i'm|im)\\s+(?:qwen[\\w.-]*|gemini[\\w.-]*|chatgpt|openai|claude|an ai language model|a language model|a large language model|an llm)\\b"),
        Regex("(?i)\\b(?:i am|i'm|im)\\s+an ai assistant(?:\\s+(?:created|developed|trained)\\s+by\\s+[\\w\\s]+)?\\b")
    )

    private val BOILERPLATE_PREFIXES = listOf(
        Regex("^(?i)(?:as an ai(?: language model)?|as a language model|as an ai model|as a large language model)[,\\s]*"),
        Regex("^(?i)(?:certainly|of course|sure thing|sure|absolutely|gladly)[!.,]\\s*"),
        Regex("^(?i)(?:i would be happy to help(?: you)?(?: with that)?|i'd be happy to help(?: you)?(?: with that)?|happy to help)[!.,]?\\s*"),
        Regex("^(?i)(?:i am here to assist you|i'm here to assist you)[!.,]?\\s*")
    )

    private val STRIP_PATTERNS = listOf(
        Regex("(?i)\\b(?:developed|created|trained|built)\\s+by\\s+(?:alibaba|google|openai|anthropic)[.,]?\\s*")
    )

    /**
     * Extracts and strips reasoning tags (<think>, <thought>, <reasoning>) from text.
     * Handles closed blocks, unclosed blocks, and provides fallback recovery if answer is inside.
     */
    fun stripThinking(raw: String): Pair<String, String> {
        var text = raw.trim()
        val thinkBlocks = mutableListOf<String>()

        // 1. Closed thinking blocks: <think>...</think>, <thought>...</thought>, <reasoning>...</reasoning>
        val closedRegex = Regex("(?i)<(think|thought|reasoning)>([\\s\\S]*?)</\\1>")
        val matches = closedRegex.findAll(text).toList()
        for (m in matches) {
            val body = m.groupValues[2].trim()
            if (body.isNotBlank()) thinkBlocks.add(body)
        }
        text = text.replace(closedRegex, "").trim()

        // 2. Unclosed thinking block: e.g. <think>... without closing tag
        val unclosedRegex = Regex("(?i)<(think|thought|reasoning)>([\\s\\S]*)$")
        val unclosedMatch = unclosedRegex.find(text)
        if (unclosedMatch != null) {
            val body = unclosedMatch.groupValues[2].trim()
            if (body.isNotBlank()) thinkBlocks.add(body)
            text = text.replace(unclosedRegex, "").trim()
        }

        // Also clean stray closing tags
        text = text.replace(Regex("(?i)</(think|thought|reasoning)>"), "").trim()

        val extractedThinking = thinkBlocks.joinToString("\n\n").trim()

        // 3. Fallback: If removing thinking left the response empty, extract the substantive conclusion from thinking
        if (text.isBlank() && extractedThinking.isNotBlank()) {
            val paragraphs = extractedThinking.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            val candidateAnswer = paragraphs.lastOrNull { p ->
                !p.startsWith("Let's", ignoreCase = true) &&
                !p.startsWith("I need to", ignoreCase = true) &&
                !p.startsWith("First,", ignoreCase = true) &&
                !p.startsWith("Thinking Process:", ignoreCase = true)
            } ?: paragraphs.lastOrNull() ?: extractedThinking
            text = candidateAnswer
        }

        return Pair(extractedThinking, text)
    }

    /**
     * Sanitizes any response into clean, natural human language for UI display.
     * Enforces JARVIS identity, removes model leaks, and strips useless boilerplate.
     */
    fun sanitize(raw: String, fallbackQuery: String? = null): String {
        var text = raw.trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true) || text.equals("null null", ignoreCase = true)) {
            return "I'm here to help. What would you like to do?"
        }

        // Strip thinking traces completely
        val (_, cleanText) = stripThinking(text)
        text = cleanText

        if (text.isEmpty() || text.equals("null", ignoreCase = true) || text.equals("null null", ignoreCase = true)) {
            return "Task completed successfully."
        }

        // Check if raw text is internal JSON or structural output
        if (isRawJsonOrInternalStructure(text)) {
            try {
                // Try parsing as JSON object
                val json = if (text.startsWith("{") && text.endsWith("}")) {
                    JSONObject(text)
                } else {
                    val start = text.indexOf('{')
                    val end = text.lastIndexOf('}')
                    if (start != -1 && end != -1 && end > start) {
                        JSONObject(text.substring(start, end + 1))
                    } else null
                }

                if (json != null) {
                    val intent = json.optString("intent", json.optString("toolCall", json.optString("action", "")))
                    val category = json.optString("category", "")
                    val directAnswer = json.optString("direct_answer", "")

                    if (directAnswer.isNotBlank() && !isRawJsonOrInternalStructure(directAnswer)) {
                        return cleanTextContent(directAnswer)
                    }

                    val query = fallbackQuery ?: json.optJSONObject("arguments")?.optString("query") ?: ""
                    val mode = AnswerSynthesizer.determineResponseMode(query, category)
                    val synthesized = AnswerSynthesizer.synthesize(query, intent, json, mode)
                    return cleanTextContent(synthesized)
                }
            } catch (_: Exception) {
                // Not standard JSON, fall through to fallback query synthesis
            }

            if (!fallbackQuery.isNullOrBlank()) {
                val mode = AnswerSynthesizer.determineResponseMode(fallbackQuery, "GENERAL")
                return cleanTextContent(AnswerSynthesizer.synthesize(fallbackQuery, "general", null, mode))
            }

            return "Task completed successfully."
        }

        return cleanTextContent(text)
    }

    /**
     * Cleans model identity leaks, replaces third-party names with JARVIS, and strips boilerplate.
     */
    fun cleanTextContent(input: String): String {
        var cleaned = input.trim()

        // 1. Strip useless leading boilerplate first
        for (prefix in BOILERPLATE_PREFIXES) {
            cleaned = cleaned.replace(prefix, "").trim()
        }

        // 2. Remove/Replace identity leaks
        for (pattern in IDENTITY_LEAK_PATTERNS) {
            cleaned = cleaned.replace(pattern, "I am JARVIS")
        }

        for (pattern in STRIP_PATTERNS) {
            cleaned = cleaned.replace(pattern, "").trim()
        }

        // Clean double "I am JARVIS" or awkward phrasing
        cleaned = cleaned.replace(Regex("(?i)I am JARVIS[.,\\s]+I am JARVIS"), "I am JARVIS")

        // Capitalize first character if needed
        if (cleaned.isNotEmpty() && cleaned[0].isLowerCase()) {
            cleaned = cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        return if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true) || cleaned.equals("null null", ignoreCase = true)) "Task completed successfully." else cleaned
    }

    /**
     * Sanitizes response into clean spoken text for TTS (no markdown asterisks, backticks, emojis).
     * Strictly enforces punchy, natural conversational speech (no huge text blobs spoken aloud).
     */
    fun sanitizeForSpeech(raw: String, fallbackQuery: String? = null): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true) || trimmed.equals("null null", ignoreCase = true)) {
            return "Task completed."
        }
        val lower = trimmed.lowercase()
        if (lower in listOf("jarvis", "hey jarvis", "hello jarvis", "hi jarvis", "ok jarvis")) {
            return "Yes?"
        }

        val sanitized = sanitize(raw, fallbackQuery)
        val clean = sanitized
            .replace(Regex("```[\\s\\S]*?```"), " ") // Remove code blocks
            .replace(Regex("https?://\\S+|www\\.\\S+"), " link ") // URLs are unreadable aloud
            .replace(Regex("[`*#_~>]"), "") // Remove markdown formatting
            .replace(Regex("[\\[\\](){}]"), " ") // Brackets trip up TTS rhythm
            .replace(Regex("[=+*/\\\\$^|]"), "") // Math/code symbols TTS spells out
            .replace(Regex("\\s&\\s"), " and ") // Ampersand → "and", not "ampersand"
            .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "") // Remove all emojis and special symbols
            .replace(Regex("([!?.,;:…])\\1+"), "$1") // Collapse !!!, ???, ... into one
            .replace(Regex("\\s+"), " ")
            .trim()

        if (clean.isBlank() || clean.equals("null", ignoreCase = true) || clean.equals("null null", ignoreCase = true)) return "Task completed."

        // Spoken brevity guard: extract at most 1-2 punchy sentences (~140 chars max)
        val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
        if (sentences.isNotEmpty()) {
            val sb = StringBuilder()
            for (s in sentences) {
                if (sb.isNotEmpty() && (sb.length + s.length > 140)) break
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(s)
            }
            val result = sb.toString().trim()
            if (result.isNotBlank()) return result
        }

        return if (clean.length > 150) clean.take(147) + "..." else clean
    }
}
