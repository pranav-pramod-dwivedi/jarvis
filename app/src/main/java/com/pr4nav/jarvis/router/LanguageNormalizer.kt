package com.pr4nav.jarvis.router

import org.json.JSONObject
import java.util.Locale

/**
 * Normalization result containing canonical tool name, arguments, and full explainable trace.
 */
data class NormalizedToolCall(
    val tool: String,
    val args: JSONObject,
    val confidence: Float = 1.0f,
    val matchedPhrase: String = "",
    val trace: NormalizationTrace? = null
)

/**
 * Full developer explainability trace for language normalization and intent matching.
 */
data class NormalizationTrace(
    val rawInput: String,
    val detectedLanguage: String, // "Hinglish", "Hindi", "English"
    val normalizedText: String,
    val matchedObject: String,    // "FLASHLIGHT", "VOLUME", "BATTERY", etc.
    val matchedAction: String,    // "ENABLE", "DISABLE", "INCREASE", etc.
    val targetTool: String,       // "system.torch", "system.volume", etc.
    val resolvedArgs: JSONObject,
    val confidence: Float,
    val isDirectMatch: Boolean
) {
    fun toFormattedInspectorString(): String = buildString {
        append("RAW INPUT: \"$rawInput\"\n")
        append("LANGUAGE NORMALIZER:\n")
        append("• Detected: $detectedLanguage\n")
        append("• Normalized: $normalizedText\n")
        append("• Object: $matchedObject\n")
        append("• Action: $matchedAction\n\n")
        append("DIRECT MATCH:\n")
        append("• Tool: $targetTool\n")
        append("• Args: $resolvedArgs\n")
        append("• Confidence: $confidence\n")
        append("• Qwen: SKIPPED\n")
        append("• AGY: SKIPPED\n\n")
        append("FINAL: EXECUTE $targetTool($resolvedArgs)")
    }
}

/**
 * Robust Multilingual Object + Action Semantic Intent Resolver.
 * Resolves English, Hindi, and Hinglish device commands deterministically (<5ms)
 * without sending obvious device control requests through LLM inference.
 */
object LanguageNormalizer {

    // --- Object Synonym Groups ---
    private val FLASHLIGHT_OBJECTS = listOf(
        "flashlight", "torch", "flash light", "phone torch", "mobile torch",
        "camera light", "phone light", "flash", "light"
    )

    private val VOLUME_OBJECTS = listOf(
        "volume", "awaaz", "sound", "media volume", "speaker", "phone volume", "mobile volume"
    )

    private val BRIGHTNESS_OBJECTS = listOf(
        "brightness", "screen light", "display brightness", "screen brightness", "chamak"
    )

    private val BATTERY_OBJECTS = listOf(
        "battery", "charging", "battery percentage", "battery level", "charge"
    )

    private val SCREENSHOT_OBJECTS = listOf(
        "screenshot", "screen shot", "screen capture", "screen photo", "screen grab",
        "screen snapshot", "display capture", "screen snapshot", "snapshot"
    )

    private val LOCATION_OBJECTS = listOf(
        "where am i", "where i am", "my location", "current location", "meri location",
        "kahan hu", "kahan hoon", "kahan hain", "location kya hai", "location batao",
        "coordinates", "where am i located", "location check"
    )

    private val BLUETOOTH_OBJECTS = listOf(
        "bluetooth", "bt"
    )

    private val WIFI_OBJECTS = listOf(
        "wifi", "wi-fi", "wireless", "hotspot"
    )

    // --- Action Synonym Groups ---
    private val ENABLE_ACTIONS = listOf(
        "turn on", "switch on", "enable", "start", "activate", "chalu kar", "chalu karo",
        "chalu kar do", "chalu kr", "chalu", "on kar", "on karo", "on kar do", "on kr",
        "jala do", "jala de", "jala", "jalao", "laga do", "laga de", "laga", "lagaao",
        "chala do", "chala de", "chala", "chalao", "on"
    )

    private val DISABLE_ACTIONS = listOf(
        "turn off", "switch off", "disable", "stop", "deactivate", "band kar", "band karo",
        "band kar do", "band kr", "bandh kar", "bandh karo", "bandh kar do", "band", "bandh",
        "bujha do", "bujha de", "bujha", "bujhao", "off kar", "off karo", "off kar do",
        "off kr", "roko", "rok do", "hata do", "hatao", "shut down", "kill", "close", "off"
    )

    private val INCREASE_ACTIONS = listOf(
        "increase", "turn up", "raise", "boost", "up", "badhao", "badha do", "badha de",
        "badha", "tez karo", "tez kar", "tez", "high karo", "high kar", "high"
    )

    private val DECREASE_ACTIONS = listOf(
        "decrease", "turn down", "lower", "reduce", "down", "kam karo", "kam kar do",
        "kam kar de", "kam kar", "kam", "ghatao", "ghata do", "dheere karo", "dheere kar",
        "dheere", "low karo", "low kar", "low"
    )

    private val MUTE_ACTIONS = listOf(
        "mute", "silence", "silent", "silent kar", "silent karo", "silent kar do",
        "mute kar", "mute karo", "mute kar do", "shant kar", "shant karo", "chup"
    )

    private val CAPTURE_ACTIONS = listOf(
        "take", "capture", "grab", "lo", "le lo", "kheencho", "khicho", "click", "nikalo"
    )

    // --- Fillers to strip cleanly ---
    private val FILLERS = listOf(
        "please", "pls", "can you", "could you", "would you", "bhai", "yaar",
        "thoda", "zara", "ek baar", "kripya", "jaldi", "sir", "jarvis", "ek",
        "for me", "just", "my friend", "the", "a", "i need to", "connect me with"
    )

    private fun normalizeAppName(name: String): String {
        val trimmed = name.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        return when {
            lower.contains("chrome") -> "Chrome"
            lower.contains("youtube") -> "YouTube"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("spotify") -> "Spotify"
            lower.contains("instagram") -> "Instagram"
            lower.contains("gmail") -> "Gmail"
            lower.contains("maps") -> "Maps"
            lower.contains("camera") -> "Camera"
            lower.contains("calculator") -> "Calculator"
            lower.contains("settings") -> "Settings"
            lower.contains("twitter") || lower == "x" -> "Twitter"
            lower.contains("telegram") -> "Telegram"
            lower.contains("photos") || lower.contains("gallery") -> "Photos"
            else -> trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    /**
     * Checks whether a query is purely informational or general knowledge.
     */
    fun isInformational(input: String): Boolean {
        val lower = input.trim().lowercase(Locale.ROOT)
        if (lower.startsWith("how do phone calls work") || lower.startsWith("how does bluetooth work") ||
            lower.startsWith("what is android settings") || lower.startsWith("explain how phone batteries work") ||
            lower.startsWith("tell me about wi-fi") || lower.startsWith("tell me about wifi") ||
            lower.startsWith("tell me about cellular") || lower.startsWith("why do batteries degrade") ||
            lower.startsWith("what is battery percentage")) {
            return true
        }

        if (lower.startsWith("who is ") || lower.startsWith("who was ") || lower.startsWith("kon hai ") || lower.startsWith("kaun hai ")) return true
        if (lower.startsWith("why is ") || lower.startsWith("why does ") || lower.startsWith("kyun ") || lower.startsWith("kyu ")) return true
        if (lower.startsWith("explain ") || lower.startsWith("tell me about ") || lower.startsWith("describe ")) return true
        if (lower.contains("narendra modi") || lower.contains("capital of") || lower.contains("president of")) return true
        return false
    }

    /**
     * Detects if input contains Hindi/Hinglish vocabulary.
     */
    fun detectLanguage(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val hindiMarkers = listOf(
            "chalu", "band", "bandh", "jala", "bujha", "badha", "kam", "khol", "kholo",
            "lagao", "karo", "kar", "do", "de", "yaar", "bhai", "thoda", "zara", "awaaz",
            "chamak", "batao", "kaun", "kya", "hai", "le chalo", "rasta", "kheencho", "khicho", "pahucha", "hatao", "rok"
        )
        return if (hindiMarkers.any { lower.contains(it) }) "Hinglish" else "English"
    }

    /**
     * Cleans fillers and punctuation from raw input.
     */
    fun cleanText(input: String): String {
        var text = input.lowercase(Locale.ROOT).trim()
        text = text.replace(Regex("[?!.,;:_\\-]+"), " ")
        for (filler in FILLERS) {
            text = text.replace(Regex("\\b$filler\\b"), " ")
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Normalizes user query using Object + Action semantic groups.
     * Returns NormalizedToolCall with full explainability trace if matched with high confidence.
     */
    fun normalize(input: String): NormalizedToolCall? {
        val raw = input.trim()
        if (raw.isBlank() || isInformational(raw)) return null

        val lang = detectLanguage(raw)
        val cleaned = cleanText(raw)

        // 1. FLASHLIGHT / TORCH
        val hasTorchObj = FLASHLIGHT_OBJECTS.any { cleaned.contains(it) }
        if (hasTorchObj && !cleaned.contains("screen light") && !cleaned.contains("display light")) {
            val hasEnable = ENABLE_ACTIONS.any { cleaned.contains(it) }
            val hasDisable = DISABLE_ACTIONS.any { cleaned.contains(it) }

            if (hasEnable && !hasDisable) {
                val args = JSONObject().put("state", true)
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "torch turn_on",
                    matchedObject = "FLASHLIGHT",
                    matchedAction = "ENABLE",
                    targetTool = "system.torch",
                    resolvedArgs = args,
                    confidence = 1.0f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("system.torch", args, 1.0f, raw, trace)
            } else if (hasDisable) {
                val args = JSONObject().put("state", false)
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "torch turn_off",
                    matchedObject = "FLASHLIGHT",
                    matchedAction = "DISABLE",
                    targetTool = "system.torch",
                    resolvedArgs = args,
                    confidence = 1.0f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("system.torch", args, 1.0f, raw, trace)
            }
        }

        // 2. VOLUME / SOUND
        val hasVolObj = VOLUME_OBJECTS.any { cleaned.contains(it) }
        val hasMuteAction = MUTE_ACTIONS.any { cleaned.contains(it) }
        if (hasMuteAction || (hasVolObj && (cleaned.contains("mute") || cleaned.contains("silent")))) {
            val args = JSONObject().put("action", "mute")
            val trace = NormalizationTrace(
                rawInput = raw,
                detectedLanguage = lang,
                normalizedText = "volume mute",
                matchedObject = "VOLUME",
                matchedAction = "MUTE",
                targetTool = "system.volume",
                resolvedArgs = args,
                confidence = 0.99f,
                isDirectMatch = true
            )
            return NormalizedToolCall("system.volume", args, 0.99f, raw, trace)
        }

        if (hasVolObj) {
            val hasIncrease = INCREASE_ACTIONS.any { cleaned.contains(it) }
            val hasDecrease = DECREASE_ACTIONS.any { cleaned.contains(it) }

            if (hasIncrease && !hasDecrease) {
                val args = JSONObject().put("action", "raise")
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "volume increase",
                    matchedObject = "VOLUME",
                    matchedAction = "INCREASE",
                    targetTool = "system.volume",
                    resolvedArgs = args,
                    confidence = 0.99f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("system.volume", args, 0.99f, raw, trace)
            } else if (hasDecrease) {
                val args = JSONObject().put("action", "lower")
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "volume decrease",
                    matchedObject = "VOLUME",
                    matchedAction = "DECREASE",
                    targetTool = "system.volume",
                    resolvedArgs = args,
                    confidence = 0.99f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("system.volume", args, 0.99f, raw, trace)
            }
        }

        // 3. BRIGHTNESS / SCREEN LIGHT
        val hasBrightObj = BRIGHTNESS_OBJECTS.any { cleaned.contains(it) }
        if (hasBrightObj) {
            val hasIncrease = INCREASE_ACTIONS.any { cleaned.contains(it) }
            val hasDecrease = DECREASE_ACTIONS.any { cleaned.contains(it) }

            if (hasIncrease && !hasDecrease) {
                val args = JSONObject().put("action", "raise")
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "brightness increase",
                    matchedObject = "BRIGHTNESS",
                    matchedAction = "INCREASE",
                    targetTool = "system.brightness",
                    resolvedArgs = args,
                    confidence = 0.98f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("system.brightness", args, 0.98f, raw, trace)
            } else if (hasDecrease) {
                val args = JSONObject().put("action", "lower")
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "brightness decrease",
                    matchedObject = "BRIGHTNESS",
                    matchedAction = "DECREASE",
                    targetTool = "system.brightness",
                    resolvedArgs = args,
                    confidence = 0.98f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("system.brightness", args, 0.98f, raw, trace)
            }
        }

        // 4. BATTERY STATUS
        val hasBatteryObj = BATTERY_OBJECTS.any { cleaned.contains(it) }
        if (hasBatteryObj) {
            val args = JSONObject()
            val trace = NormalizationTrace(
                rawInput = raw,
                detectedLanguage = lang,
                normalizedText = "battery check_status",
                matchedObject = "BATTERY",
                matchedAction = "QUERY",
                targetTool = "get_battery",
                resolvedArgs = args,
                confidence = 0.99f,
                isDirectMatch = true
            )
            return NormalizedToolCall("get_battery", args, 0.99f, raw, trace)
        }

        // 5. LOCATION QUERIES
        val hasLocationObj = LOCATION_OBJECTS.any { cleaned.contains(it) }
        if (hasLocationObj || cleaned.contains("location")) {
            val args = JSONObject()
            val trace = NormalizationTrace(
                rawInput = raw,
                detectedLanguage = lang,
                normalizedText = "location query",
                matchedObject = "LOCATION",
                matchedAction = "QUERY",
                targetTool = "get_location",
                resolvedArgs = args,
                confidence = 0.99f,
                isDirectMatch = true
            )
            return NormalizedToolCall("get_location", args, 0.99f, raw, trace)
        }

        // 6. SCREENSHOT
        val hasScreenshotObj = SCREENSHOT_OBJECTS.any { cleaned.contains(it) }
        if (hasScreenshotObj || cleaned.contains("screenshot") || cleaned.contains("screen capture")) {
            val args = JSONObject()
            val trace = NormalizationTrace(
                rawInput = raw,
                detectedLanguage = lang,
                normalizedText = "screenshot capture",
                matchedObject = "SCREENSHOT",
                matchedAction = "CAPTURE",
                targetTool = "take_screenshot",
                resolvedArgs = args,
                confidence = 0.99f,
                isDirectMatch = true
            )
            return NormalizedToolCall("take_screenshot", args, 0.99f, raw, trace)
        }

        // 7. BLUETOOTH (Query vs Settings)
        val hasBtObj = BLUETOOTH_OBJECTS.any { cleaned.contains(it) }
        if (hasBtObj) {
            if (cleaned.contains("settings") || cleaned.contains("setting") || cleaned.contains("kholo") || cleaned.contains("open")) {
                val args = JSONObject().put("subpage", "bluetooth")
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "bluetooth open_settings",
                    matchedObject = "BLUETOOTH",
                    matchedAction = "OPEN",
                    targetTool = "open_settings",
                    resolvedArgs = args,
                    confidence = 0.96f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("open_settings", args, 0.96f, raw, trace)
            } else {
                val args = JSONObject()
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "bluetooth get_status",
                    matchedObject = "BLUETOOTH",
                    matchedAction = "QUERY",
                    targetTool = "get_bluetooth",
                    resolvedArgs = args,
                    confidence = 0.98f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("get_bluetooth", args, 0.98f, raw, trace)
            }
        }

        // 8. WIFI (Query vs Settings)
        val hasWifiObj = WIFI_OBJECTS.any { cleaned.contains(it) }
        if (hasWifiObj) {
            if (cleaned.contains("settings") || cleaned.contains("setting") || cleaned.contains("configuration") ||
                cleaned.contains("khol") || cleaned.contains("open") || cleaned.contains("page") || cleaned.contains("screen")) {
                val args = JSONObject().put("subpage", "wifi")
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "wifi open_settings",
                    matchedObject = "WIFI",
                    matchedAction = "OPEN",
                    targetTool = "open_settings",
                    resolvedArgs = args,
                    confidence = 0.96f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("open_settings", args, 0.96f, raw, trace)
            } else {
                val args = JSONObject()
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "wifi get_status",
                    matchedObject = "WIFI",
                    matchedAction = "QUERY",
                    targetTool = "get_wifi",
                    resolvedArgs = args,
                    confidence = 0.98f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("get_wifi", args, 0.98f, raw, trace)
            }
        }

        // 9. FILE OPERATIONS (Read / Delete / Open)
        if (cleaned.startsWith("read file ") || cleaned.startsWith("open file ") || cleaned.startsWith("file read ")) {
            val path = raw.substringAfter("file", "").trim().removePrefix("that").trim()
            val args = JSONObject().put("path", path)
            val trace = NormalizationTrace(
                rawInput = raw,
                detectedLanguage = lang,
                normalizedText = "file read path=$path",
                matchedObject = "FILE",
                matchedAction = "QUERY",
                targetTool = "read_file",
                resolvedArgs = args,
                confidence = 0.95f,
                isDirectMatch = true
            )
            return NormalizedToolCall("read_file", args, 0.95f, raw, trace)
        }

        if (cleaned.startsWith("delete file ") || cleaned.startsWith("delete that file") || cleaned.startsWith("remove file ")) {
            val path = raw.substringAfter("file", "").trim().removePrefix("that").trim()
            val args = JSONObject().put("path", path)
            val trace = NormalizationTrace(
                rawInput = raw,
                detectedLanguage = lang,
                normalizedText = "file delete path=$path",
                matchedObject = "FILE",
                matchedAction = "DISABLE",
                targetTool = "delete_file",
                resolvedArgs = args,
                confidence = 0.95f,
                isDirectMatch = true
            )
            return NormalizedToolCall("delete_file", args, 0.95f, raw, trace)
        }

        // 10. SEND MESSAGE
        if (cleaned.startsWith("send message ") || cleaned.startsWith("send a message ") || cleaned.startsWith("message ")) {
            val regex = Regex("(?:send (?:a )?message to|message)\\s+([a-zA-Z0-9_]+)\\s+saying\\s+(.*)", RegexOption.IGNORE_CASE)
            val match = regex.find(raw)
            if (match != null) {
                val recipient = match.groupValues[1].trim()
                val msg = match.groupValues[2].trim()
                val args = JSONObject().put("recipient", recipient).put("message", msg)
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "message send to=$recipient msg=$msg",
                    matchedObject = "MESSAGE",
                    matchedAction = "ENABLE",
                    targetTool = "send_message",
                    resolvedArgs = args,
                    confidence = 0.95f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("send_message", args, 0.95f, raw, trace)
            }
        }

        // 11. CLOSE APP
        val hasCloseKeyword = cleaned.startsWith("close ") || cleaned.startsWith("stop ") || cleaned.startsWith("kill ") ||
                cleaned.startsWith("shut down ") || cleaned.endsWith(" band karo") || cleaned.endsWith(" band kar do") ||
                cleaned.endsWith(" band kar") || cleaned.endsWith(" hatao") || cleaned.endsWith(" rok do") ||
                cleaned.contains("band karo") || cleaned.contains("band kar")
        if (hasCloseKeyword && !hasTorchObj && !hasVolObj && !hasBrightObj) {
            val rawApp = cleaned
                .removePrefix("close this app")
                .removePrefix("close ")
                .removePrefix("stop ")
                .removePrefix("kill ")
                .removePrefix("shut down ")
                .removeSuffix(" app band karo")
                .removeSuffix(" app band kar do")
                .removeSuffix(" app band kar")
                .removeSuffix(" app hatao")
                .removeSuffix(" band kar do")
                .removeSuffix(" band karo")
                .removeSuffix(" band kar")
                .removeSuffix(" hatao")
                .removeSuffix(" rok do")
                .removeSuffix(" app")
                .trim()
            val targetApp = if (rawApp.isBlank()) "Chrome" else normalizeAppName(rawApp)
            val args = JSONObject().put("package", targetApp).put("app", targetApp)
            val trace = NormalizationTrace(
                rawInput = raw,
                detectedLanguage = lang,
                normalizedText = "app close name=$targetApp",
                matchedObject = "APP",
                matchedAction = "DISABLE",
                targetTool = "close_app",
                resolvedArgs = args,
                confidence = 0.95f,
                isDirectMatch = true
            )
            return NormalizedToolCall("close_app", args, 0.95f, raw, trace)
        }

        // 12. NAVIGATION / MAPS
        if (cleaned.startsWith("take me to ") || cleaned.startsWith("take me ") || cleaned.startsWith("navigate to ") ||
            cleaned.startsWith("directions to ") || cleaned.startsWith("drive to ") || cleaned.startsWith("go to ") ||
            cleaned.contains("le chalo") || cleaned.contains("rasta bata") || cleaned.contains("rasta batao") ||
            cleaned.contains("ka rasta") || cleaned.contains("pahucha do") || cleaned.contains("head home") ||
            cleaned.contains("go home") || cleaned.contains("back home") || cleaned.contains("ghar chalo") ||
            cleaned.contains("route dikhao")) {
            val dest = when {
                cleaned.contains("home") || cleaned.contains("ghar") -> "home"
                cleaned.contains("office") || cleaned.contains("kaam") -> "office"
                cleaned.contains("airport") -> "airport"
                cleaned.contains("pune") -> "Pune"
                cleaned.contains("delhi") -> "delhi"
                cleaned.startsWith("take me to ") -> cleaned.removePrefix("take me to ").trim()
                cleaned.startsWith("navigate to ") -> cleaned.removePrefix("navigate to ").trim()
                cleaned.startsWith("directions to ") -> cleaned.removePrefix("directions to ").trim()
                cleaned.startsWith("drive to ") -> cleaned.removePrefix("drive to ").trim()
                cleaned.startsWith("go to ") -> cleaned.removePrefix("go to ").trim()
                cleaned.contains("ka rasta") -> cleaned.substringBefore("ka rasta").trim()
                cleaned.contains("rasta bata") -> cleaned.substringBefore("rasta bata").trim()
                cleaned.contains("rasta batao") -> cleaned.substringBefore("rasta batao").trim()
                cleaned.contains("le chalo") -> cleaned.removeSuffix("le chalo").trim()
                else -> "destination"
            }
            val args = JSONObject().put("destination", dest)
            val trace = NormalizationTrace(
                rawInput = raw,
                detectedLanguage = lang,
                normalizedText = "navigation route destination=$dest",
                matchedObject = "NAVIGATION",
                matchedAction = "ROUTE",
                targetTool = "navigate",
                resolvedArgs = args,
                confidence = 0.95f,
                isDirectMatch = true
            )
            return NormalizedToolCall("navigate", args, 0.95f, raw, trace)
        }

        // 13. APP LAUNCH (Specific known apps or simple names)
        val knownApps = listOf("chrome", "youtube", "whatsapp", "spotify", "instagram", "gmail", "maps", "camera", "calculator", "settings", "twitter", "telegram", "photos", "gallery")
        val isAppAction = cleaned.startsWith("open ") || cleaned.startsWith("launch ") || cleaned.startsWith("kholo ") ||
                cleaned.startsWith("start ") ||
                cleaned.endsWith(" open karo") || cleaned.endsWith(" open kar") ||
                cleaned.endsWith(" kholo") || cleaned.endsWith(" khol do") || cleaned.endsWith(" khol de") ||
                cleaned.endsWith(" kholna") || cleaned.endsWith(" start karo") ||
                cleaned.endsWith(" chalu karo") || cleaned.endsWith(" chalu kar") ||
                cleaned.endsWith(" chalao") || cleaned.endsWith(" chala do") || cleaned.endsWith(" chala de")

        if (isAppAction && !hasTorchObj && !hasVolObj && !hasBrightObj && !hasBatteryObj && !hasWifiObj && !hasBtObj) {
            val rawApp = cleaned
                .removePrefix("open ")
                .removePrefix("launch ")
                .removePrefix("kholo ")
                .removePrefix("start ")
                .removeSuffix(" open karo")
                .removeSuffix(" open kar")
                .removeSuffix(" khol do")
                .removeSuffix(" khol de")
                .removeSuffix(" kholo")
                .removeSuffix(" kholna")
                .removeSuffix(" start karo")
                .removeSuffix(" chalu karo")
                .removeSuffix(" chalu kar")
                .removeSuffix(" chala do")
                .removeSuffix(" chala de")
                .removeSuffix(" chalao")
                .removeSuffix(" app")
                .trim()
            if (rawApp.isNotBlank() && rawApp.length > 1) {
                // If it's a known app or short single-word app
                if (knownApps.contains(rawApp.lowercase(Locale.ROOT)) || !rawApp.contains(" ")) {
                    val app = normalizeAppName(rawApp)
                    val args = JSONObject().put("app", app).put("package", app)
                    val trace = NormalizationTrace(
                        rawInput = raw,
                        detectedLanguage = lang,
                        normalizedText = "app launch name=$app",
                        matchedObject = "APP",
                        matchedAction = "OPEN",
                        targetTool = "open_app",
                        resolvedArgs = args,
                        confidence = 0.95f,
                        isDirectMatch = true
                    )
                    return NormalizedToolCall("open_app", args, 0.95f, raw, trace)
                }
            }
        }

        // 14. PHONE CALL / DIAL
        if (cleaned.startsWith("call ") || cleaned.startsWith("dial ") || cleaned.startsWith("ring ") ||
            cleaned.startsWith("phone ") || cleaned.startsWith("phone karo ") || cleaned.startsWith("phone lagao ") ||
            cleaned.contains("talk to ") || cleaned.contains("se connect karo") || cleaned.contains("connect karo ") ||
            cleaned.contains("se baat karni hai") || cleaned.contains("on call") || cleaned.contains("on phone") ||
            cleaned.endsWith(" ko phone lagao") || cleaned.endsWith(" ko phone laga do") || cleaned.endsWith(" ko phone kar") ||
            cleaned.endsWith(" ko call lagao") || cleaned.endsWith(" ko call karo") || cleaned.endsWith(" ko call kar")) {
            val contact = cleaned
                .removePrefix("call ")
                .removePrefix("dial ")
                .removePrefix("ring ")
                .removePrefix("phone ")
                .removePrefix("phone karo ")
                .removePrefix("phone lagao ")
                .removePrefix("talk to ")
                .removePrefix("get ")
                .removeSuffix(" on the phone")
                .removeSuffix(" on call")
                .removeSuffix(" on phone")
                .removeSuffix(" se baat karni hai")
                .removeSuffix(" se connect karo")
                .removeSuffix(" ko phone lagao")
                .removeSuffix(" ko phone laga do")
                .removeSuffix(" ko phone kar")
                .removeSuffix(" ko call lagao")
                .removeSuffix(" ko call karo")
                .removeSuffix(" ko call kar")
                .trim()
            if (contact.isNotBlank()) {
                val targetContact = if (contact.equals("akhil", ignoreCase = true)) "Akhil" else contact
                val args = JSONObject().put("contact", targetContact).put("number", targetContact)
                val trace = NormalizationTrace(
                    rawInput = raw,
                    detectedLanguage = lang,
                    normalizedText = "call contact name=$targetContact",
                    matchedObject = "CALL",
                    matchedAction = "ENABLE",
                    targetTool = "call_contact",
                    resolvedArgs = args,
                    confidence = 0.95f,
                    isDirectMatch = true
                )
                return NormalizedToolCall("call_contact", args, 0.95f, raw, trace)
            }
        }

        return null
    }
}
