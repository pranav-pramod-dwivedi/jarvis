package com.pr4nav.jarvis.intent

import java.util.regex.Pattern

enum class IntentCategory {
    DEVICE_CONTROL,
    COMMUNICATION,
    NAVIGATION,
    MEDIA,
    FILES,
    APPS,
    SETTINGS,
    AUTOMATION,
    INFORMATION,
    CONVERSATION,
    CODING,
    UNKNOWN
}

enum class ResponseType {
    ACTION,
    ANSWER,
    CLARIFICATION,
    UNKNOWN
}

data class ClassifiedIntent(
    val category: IntentCategory,
    val responseType: ResponseType,
    val directAnswer: String? = null,
    val confidence: Float = 1.0f,
    val explanation: String = ""
)

/**
 * Top-level intent categorizer.
 * Separates ACTION (tool call) from ANSWER (textual/math response),
 * and prevents informational questions ("Who is Narendra Modi?") from falling into MEDIA or DEVICE tools.
 */
object IntentClassifier {

    private val mathPattern = Pattern.compile("^what\\s+is\\s+([0-9+\\-*/^().\\s]+)\\??$", Pattern.CASE_INSENSITIVE)

    private val informationalPrefixes = listOf(
        "who is", "who was", "what is", "what was", "what are", "when was", "where is",
        "why is", "why did", "how does", "how do", "explain", "tell me about", "describe",
        "koun hai", "kya hai", "kahan hai", "kaise hota hai", "ke bare mein batao"
    )

    private val codingKeywords = listOf(
        "kotlin", "java", "python", "gradle", "nullpointerexception", "crash", "bug",
        "compile", "stacktrace", "function", "class", "algorithm", "code", "syntax error"
    )

    private val mediaKeywords = listOf(
        "play", "song", "music", "track", "listen", "video", "gaana", "chalao", "baja do"
    )

    fun classify(prompt: String): ClassifiedIntent {
        val clean = prompt.trim().lowercase()
        if (clean.isBlank()) {
            return ClassifiedIntent(IntentCategory.UNKNOWN, ResponseType.UNKNOWN, explanation = "Empty prompt")
        }

        // 1. Math computation (Direct local answer without tool invocation)
        val mathMatcher = mathPattern.matcher(clean)
        if (mathMatcher.matches()) {
            val expr = mathMatcher.group(1)?.trim() ?: ""
            val solved = evaluateSimpleMath(expr)
            if (solved != null) {
                return ClassifiedIntent(
                    category = IntentCategory.CONVERSATION,
                    responseType = ResponseType.ANSWER,
                    directAnswer = "$expr = $solved",
                    confidence = 1.0f,
                    explanation = "Direct mathematical calculation"
                )
            }
        }

        // 2. Greetings and identity
        if (clean in listOf("hi", "hello", "hey", "namaste", "good morning", "good evening", "how are you", "who are you", "what is your name")) {
            return ClassifiedIntent(
                category = IntentCategory.CONVERSATION,
                responseType = ResponseType.ANSWER,
                directAnswer = "Hello! I am JARVIS, your intelligent autonomous assistant. How can I help you?",
                confidence = 1.0f,
                explanation = "Conversational greeting"
            )
        }

        // 3. Coding & Debugging
        for (kw in codingKeywords) {
            if (clean.contains(kw)) {
                return ClassifiedIntent(
                    category = IntentCategory.CODING,
                    responseType = ResponseType.ANSWER,
                    confidence = 0.95f,
                    explanation = "Software engineering and code reasoning"
                )
            }
        }

        // 4. Device Controls (Flashlight, Volume, Battery, Brightness, Screenshot)
        if (clean.contains("flashlight") || clean.contains("torch") || clean.contains("light on") || clean.contains("light off")) {
            return ClassifiedIntent(IntentCategory.DEVICE_CONTROL, ResponseType.ACTION, confidence = 1.0f, explanation = "Torch/Flashlight control")
        }
        if (clean.contains("volume") || clean.contains("awaaz") || clean.contains("mute") || clean.contains("silent")) {
            return ClassifiedIntent(IntentCategory.DEVICE_CONTROL, ResponseType.ACTION, confidence = 0.98f, explanation = "Volume control")
        }
        if (clean.contains("battery") || clean.contains("charge kitna")) {
            return ClassifiedIntent(IntentCategory.DEVICE_CONTROL, ResponseType.ACTION, confidence = 0.98f, explanation = "Battery check")
        }
        if (clean.contains("screenshot")) {
            return ClassifiedIntent(IntentCategory.DEVICE_CONTROL, ResponseType.ACTION, confidence = 0.99f, explanation = "Screenshot capture")
        }

        // 5. Communication (Call, SMS, Contact)
        if (clean.startsWith("call ") || clean.contains("phone lagao") || clean.contains("call karo") || clean.startsWith("message ") || clean.startsWith("sms ") || clean.startsWith("text ")) {
            return ClassifiedIntent(IntentCategory.COMMUNICATION, ResponseType.ACTION, confidence = 0.95f, explanation = "Telephony / Messaging")
        }

        // 6. Navigation
        if (clean.startsWith("take me to") || clean.startsWith("navigate") || clean.contains("rasta bata") || clean.contains("directions to")) {
            return ClassifiedIntent(IntentCategory.NAVIGATION, ResponseType.ACTION, confidence = 0.95f, explanation = "GPS Navigation")
        }

        // 7. Apps
        if (clean.startsWith("open ") || clean.startsWith("launch ") || clean.endsWith("kholo") || clean.startsWith("close ") || clean.endsWith("band karo")) {
            // Distinguish open settings from open app
            if (clean.contains("settings") || clean.contains("setting")) {
                return ClassifiedIntent(IntentCategory.SETTINGS, ResponseType.ACTION, confidence = 0.98f, explanation = "Settings subpage")
            }
            return ClassifiedIntent(IntentCategory.APPS, ResponseType.ACTION, confidence = 0.95f, explanation = "App management")
        }

        // 8. Media (Must have media verb/noun e.g. "play", "song", "music", "gaana")
        val isExplicitMedia = mediaKeywords.any { clean.startsWith("$it ") || clean.endsWith(" $it") || clean.contains(" $it ") }
        if (isExplicitMedia) {
            val isInfoQuestion = informationalPrefixes.any { clean.startsWith(it) }
            if (!isInfoQuestion) {
                return ClassifiedIntent(IntentCategory.MEDIA, ResponseType.ACTION, confidence = 0.95f, explanation = "Media playback")
            }
        }

        // 9. Files
        if (clean.contains("file") || clean.contains("download") || clean.contains("pdf") || clean.startsWith("cat ") || clean.startsWith("read ")) {
            return ClassifiedIntent(IntentCategory.FILES, ResponseType.ACTION, confidence = 0.90f, explanation = "File management")
        }

        // 10. Information & General Knowledge (e.g. "Who is Narendra Modi?", "Where is Delhi?")
        val isInformational = informationalPrefixes.any { clean.startsWith(it) }
        if (isInformational) {
            return ClassifiedIntent(
                category = IntentCategory.INFORMATION,
                responseType = ResponseType.ANSWER,
                confidence = 0.95f,
                explanation = "Informational general knowledge question"
            )
        }

        return ClassifiedIntent(IntentCategory.UNKNOWN, ResponseType.UNKNOWN, explanation = "Unrecognized intent")
    }

    private fun evaluateSimpleMath(expr: String): String? {
        val clean = expr.replace(" ", "").trim()
        val addMatch = Regex("^([0-9.]+)\\+([0-9.]+)$").find(clean)
        if (addMatch != null) {
            val a = addMatch.groupValues[1].toDoubleOrNull() ?: return null
            val b = addMatch.groupValues[2].toDoubleOrNull() ?: return null
            val res = a + b
            return if (res % 1.0 == 0.0) res.toInt().toString() else res.toString()
        }
        val subMatch = Regex("^([0-9.]+)-([0-9.]+)$").find(clean)
        if (subMatch != null) {
            val a = subMatch.groupValues[1].toDoubleOrNull() ?: return null
            val b = subMatch.groupValues[2].toDoubleOrNull() ?: return null
            val res = a - b
            return if (res % 1.0 == 0.0) res.toInt().toString() else res.toString()
        }
        val mulMatch = Regex("^([0-9.]+)[*x]([0-9.]+)$", RegexOption.IGNORE_CASE).find(clean)
        if (mulMatch != null) {
            val a = mulMatch.groupValues[1].toDoubleOrNull() ?: return null
            val b = mulMatch.groupValues[2].toDoubleOrNull() ?: return null
            val res = a * b
            return if (res % 1.0 == 0.0) res.toInt().toString() else res.toString()
        }
        val divMatch = Regex("^([0-9.]+)/([0-9.]+)$").find(clean)
        if (divMatch != null) {
            val a = divMatch.groupValues[1].toDoubleOrNull() ?: return null
            val b = divMatch.groupValues[2].toDoubleOrNull() ?: return null
            if (b == 0.0) return "undefined (division by zero)"
            val res = a / b
            return if (res % 1.0 == 0.0) res.toInt().toString() else res.toString()
        }
        return null
    }
}
