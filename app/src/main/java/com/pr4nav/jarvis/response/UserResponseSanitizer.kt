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

    /**
     * Sanitizes any response into clean, natural human language for UI display.
     */
    fun sanitize(raw: String, fallbackQuery: String? = null): String {
        var text = raw.trim()
        if (text.isEmpty()) return "I'm here to help. What would you like to do?"

        // Strip <think>...</think> blocks if present
        if (text.contains("<think>")) {
            text = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
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
                        return directAnswer
                    }

                    val query = fallbackQuery ?: json.optJSONObject("arguments")?.optString("query") ?: ""
                    val mode = AnswerSynthesizer.determineResponseMode(query, category)
                    return AnswerSynthesizer.synthesize(query, intent, json, mode)
                }
            } catch (_: Exception) {
                // Not standard JSON, fall through to fallback query synthesis
            }

            if (!fallbackQuery.isNullOrBlank()) {
                val mode = AnswerSynthesizer.determineResponseMode(fallbackQuery, "GENERAL")
                return AnswerSynthesizer.synthesize(fallbackQuery, "general", null, mode)
            }

            return "Task completed successfully."
        }

        // Clean any leading model engine badges for UI presentation
        return text
    }

    /**
     * Sanitizes response into clean spoken text for TTS (no markdown asterisks, backticks, emojis).
     */
    fun sanitizeForSpeech(raw: String, fallbackQuery: String? = null): String {
        val sanitized = sanitize(raw, fallbackQuery)
        return sanitized
            .replace(Regex("```[\\s\\S]*?```"), "") // Remove code blocks
            .replace(Regex("[`*#_~>]"), "")          // Remove markdown formatting
            .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "") // Remove all emojis and special symbols
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
