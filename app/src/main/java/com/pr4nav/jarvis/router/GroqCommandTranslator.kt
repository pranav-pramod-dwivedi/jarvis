package com.pr4nav.jarvis.router

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.llm.GroqClient
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * Groq Command Translator & Needle Fallback Bridge.
 *
 * Uses Groq LLaMA 3.3 70B / LLaMA 3.1 8B to convert open-ended natural language requests into
 * supported structured JARVIS commands and executes them via the deterministic execution layer.
 */
object GroqCommandTranslator {

    private const val TAG = "GroqCommandTranslator"

    /**
     * Translates a user request into a supported structured JARVIS tool call.
     */
    fun translate(
        context: Context,
        userRequest: String,
        onSuccess: (NormalizedToolCall) -> Unit,
        onError: (String) -> Unit
    ) {
        val prompt = """Convert the user's request into one supported JARVIS command.

User request:
"$userRequest"

Supported command schema:
bluetooth(enable)
bluetooth(disable)
bluetooth(status)
torch(enable)
torch(disable)
volume(raise)
volume(lower)
volume(mute)
volume(set: <level>)
wifi(enable)
wifi(disable)
wifi(status)
open_app(app: <name>)
close_app(package: <name>)
call(contact: <name_or_number>)
send_message(recipient: <name>, message: <text>)
navigate(destination: <place>)
screenshot()
battery()
location()
file_read(path: <filepath>)
file_delete(path: <filepath>)
file_search(query: <search_term>)
open_settings(subpage: <name>)
run_command(command: <cmd>)

Return ONLY the command."""

        GroqClient.query(
            context = context,
            prompt = prompt,
            forceShellCapability = false,
            onSuccess = { groqRes ->
                val raw = groqRes.response.trim()
                Log.i(TAG, "Groq Translator raw response: $raw")
                val parsed = parseCommandToToolCall(raw, userRequest)
                if (parsed != null) {
                    onSuccess(parsed)
                } else {
                    onError("Failed to parse translated command from Groq: $raw")
                }
            },
            onError = { err ->
                // Fallback to offline grammar parsing
                val offlineCall = parseOfflineFallback(userRequest)
                if (offlineCall != null) {
                    onSuccess(offlineCall)
                } else {
                    onError("Groq translator unreachable and offline parse failed: $err")
                }
            }
        )
    }

    /**
     * Parses a structured command syntax string into a NormalizedToolCall.
     */
    fun parseCommandToToolCall(commandText: String, originalQuery: String = ""): NormalizedToolCall? {
        val clean = commandText
            .replace("```json", "")
            .replace("```", "")
            .trim()

        // 1. JSON object format fallback
        if (clean.startsWith("{") && clean.endsWith("}")) {
            try {
                val json = JSONObject(clean)
                val toolName = json.optString("intent", json.optString("tool", json.optString("action", "")))
                val args = json.optJSONObject("arguments") ?: JSONObject()
                if (toolName.isNotBlank()) {
                    return NormalizedToolCall(
                        tool = mapToolName(toolName),
                        args = args,
                        confidence = 0.95f,
                        matchedPhrase = originalQuery
                    )
                }
            } catch (_: Exception) {}
        }

        // 2. Canonical functional syntax: name(arg) or name(key: val)
        val funcRegex = Pattern.compile("([a-zA-Z0-9_.-]+)\\s*\\((.*?)\\)", Pattern.DOTALL)
        val matcher = funcRegex.matcher(clean)
        if (matcher.find()) {
            val funcName = matcher.group(1)?.lowercase(Locale.US) ?: ""
            val rawArgs = matcher.group(2)?.trim() ?: ""

            when (funcName) {
                "bluetooth" -> {
                    val lowerArg = rawArgs.lowercase(Locale.US)
                    return when {
                        lowerArg.contains("enable") || lowerArg.contains("on") || lowerArg.contains("true") ->
                            NormalizedToolCall("system.bluetooth", JSONObject().put("state", true), 0.98f, originalQuery)
                        lowerArg.contains("disable") || lowerArg.contains("off") || lowerArg.contains("false") ->
                            NormalizedToolCall("system.bluetooth", JSONObject().put("state", false), 0.98f, originalQuery)
                        else ->
                            NormalizedToolCall("get_bluetooth", JSONObject(), 0.98f, originalQuery)
                    }
                }

                "torch", "flashlight" -> {
                    val lowerArg = rawArgs.lowercase(Locale.US)
                    val state = !lowerArg.contains("disable") && !lowerArg.contains("off") && !lowerArg.contains("false")
                    return NormalizedToolCall("system.torch", JSONObject().put("state", state), 0.98f, originalQuery)
                }

                "volume" -> {
                    val lowerArg = rawArgs.lowercase(Locale.US)
                    val action = when {
                        lowerArg.contains("raise") || lowerArg.contains("up") -> "raise"
                        lowerArg.contains("lower") || lowerArg.contains("down") -> "lower"
                        lowerArg.contains("mute") -> "mute"
                        else -> "adjust"
                    }
                    val level = extractNumber(rawArgs)
                    val args = JSONObject().put("action", action)
                    if (level != null) args.put("level", level)
                    return NormalizedToolCall("system.volume", args, 0.98f, originalQuery)
                }

                "wifi", "wi-fi" -> {
                    val lowerArg = rawArgs.lowercase(Locale.US)
                    return if (lowerArg.contains("enable") || lowerArg.contains("on") || lowerArg.contains("disable") || lowerArg.contains("off")) {
                        NormalizedToolCall("open_settings", JSONObject().put("subpage", "wifi"), 0.96f, originalQuery)
                    } else {
                        NormalizedToolCall("get_wifi", JSONObject(), 0.98f, originalQuery)
                    }
                }

                "open_app", "app" -> {
                    val appName = extractArgValue(rawArgs, "app").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("open_app", JSONObject().put("app", appName), 0.95f, originalQuery)
                }

                "close_app" -> {
                    val pkgName = extractArgValue(rawArgs, "package").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("close_app", JSONObject().put("package", pkgName), 0.95f, originalQuery)
                }

                "call" -> {
                    val contact = extractArgValue(rawArgs, "contact").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("call_contact", JSONObject().put("number", contact), 0.95f, originalQuery)
                }

                "send_message", "message", "sms" -> {
                    val rec = extractArgValue(rawArgs, "recipient")
                    val msg = extractArgValue(rawArgs, "message")
                    return NormalizedToolCall("send_message", JSONObject().put("recipient", rec).put("message", msg), 0.95f, originalQuery)
                }

                "navigate" -> {
                    val dest = extractArgValue(rawArgs, "destination").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("navigate", JSONObject().put("destination", dest), 0.95f, originalQuery)
                }

                "screenshot" -> {
                    return NormalizedToolCall("take_screenshot", JSONObject(), 0.99f, originalQuery)
                }

                "battery" -> {
                    return NormalizedToolCall("get_battery", JSONObject(), 0.99f, originalQuery)
                }

                "location" -> {
                    return NormalizedToolCall("get_location", JSONObject(), 0.99f, originalQuery)
                }

                "file_read", "read_file" -> {
                    val path = extractArgValue(rawArgs, "path").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("read_file", JSONObject().put("path", path), 0.95f, originalQuery)
                }

                "file_delete", "delete_file" -> {
                    val path = extractArgValue(rawArgs, "path").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("delete_file", JSONObject().put("path", path), 0.95f, originalQuery)
                }

                "file_search", "search_files" -> {
                    val query = extractArgValue(rawArgs, "query").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("search_files", JSONObject().put("query", query), 0.95f, originalQuery)
                }

                "open_settings", "settings" -> {
                    val subpage = extractArgValue(rawArgs, "subpage").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("open_settings", JSONObject().put("subpage", subpage), 0.96f, originalQuery)
                }

                "run_command", "shell" -> {
                    val cmd = extractArgValue(rawArgs, "command").ifEmpty { stripQuotes(rawArgs) }
                    return NormalizedToolCall("run_command", JSONObject().put("command", cmd), 0.95f, originalQuery)
                }
            }
        }

        return parseOfflineFallback(originalQuery.ifEmpty { clean })
    }

    private fun parseOfflineFallback(query: String): NormalizedToolCall? {
        val lower = query.lowercase(Locale.US).trim()
        return when {
            lower.contains("bluetooth") -> {
                val state = !lower.contains("off") && !lower.contains("disable") && !lower.contains("stop")
                if (lower.contains("on") || lower.contains("off") || lower.contains("enable") || lower.contains("disable")) {
                    NormalizedToolCall("system.bluetooth", JSONObject().put("state", state), 0.92f, query)
                } else {
                    NormalizedToolCall("get_bluetooth", JSONObject(), 0.95f, query)
                }
            }
            lower.contains("torch") || lower.contains("flashlight") -> {
                val state = !lower.contains("off") && !lower.contains("disable") && !lower.contains("stop")
                NormalizedToolCall("system.torch", JSONObject().put("state", state), 0.95f, query)
            }
            lower.contains("volume") -> {
                val action = if (lower.contains("down") || lower.contains("lower")) "lower" else "raise"
                NormalizedToolCall("system.volume", JSONObject().put("action", action), 0.95f, query)
            }
            lower.contains("screenshot") -> {
                NormalizedToolCall("take_screenshot", JSONObject(), 0.99f, query)
            }
            lower.contains("battery") -> {
                NormalizedToolCall("get_battery", JSONObject(), 0.99f, query)
            }
            else -> null
        }
    }

    private fun mapToolName(raw: String): String {
        return when (raw.lowercase(Locale.US)) {
            "bluetooth", "set_bluetooth" -> "system.bluetooth"
            "torch", "set_flashlight" -> "system.torch"
            "volume" -> "system.volume"
            "battery" -> "get_battery"
            "wifi" -> "get_wifi"
            "screenshot" -> "take_screenshot"
            "location" -> "get_location"
            "call" -> "call_contact"
            else -> raw
        }
    }

    private fun extractArgValue(raw: String, key: String): String {
        val pattern = Pattern.compile("(?:$key\\s*[:=]\\s*[\"']?([^\"',)]+)[\"']?)", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(raw)
        return if (matcher.find()) matcher.group(1)?.trim() ?: "" else ""
    }

    private fun extractNumber(raw: String): Int? {
        val pattern = Pattern.compile("(\\d+)")
        val matcher = pattern.matcher(raw)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
    }

    private fun stripQuotes(s: String): String = s.removeSurrounding("\"").removeSurrounding("'").trim()
}
