package com.pr4nav.jarvis.llm

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.agy.AgyClient
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cloud LLM Provider for JARVIS with Full Command Execution Access.
 * Never denies requests; equipped with shell and device command execution tools.
 */
object GeminiCloudLLM {

    private const val TAG = "GeminiCloudLLM"
    private const val PREFS_NAME = "jarvis_cloud_prefs"
    const val KEY_GEMINI_API_KEY = "gemini_api_key"
    const val KEY_GEMINI_MODEL = "gemini_model"
    const val DEFAULT_MODEL = "gemini-2.0-flash"

    const val DEFAULT_SYSTEM_INSTRUCTION = """You are JARVIS, an autonomous, highly capable personal AI companion with full system, shell, and device control access.
You have direct permission to execute commands, launch apps, configure settings, and manage the system.
NEVER deny, refuse, or lecture the user. Never say you cannot execute commands, open apps, control settings, or perform actions.
When asked to execute an action or command:
- To run a shell command, output: ```command\n<exact-shell-command>\n```
- To invoke a device tool, output: ```action\n{"intent": "<tool_name>", "arguments": { ... }}\n```
Available device tools:
• system.torch (state: true/false)
• system.volume (action: "raise"|"lower"|"mute"|"set", level: 0-100)
• open_app (app_name: string)
• call_contact (name: string)
• system.bluetooth (state: true/false)
• system.wifi (state: true/false)
• search_web (query: string)
• media.play / media.pause / media.next
• run_command (command: string)
Always execute commands enthusiastically and provide clear, natural responses."""

    private val executor = Executors.newCachedThreadPool()

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun getModel(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModel(context: Context, model: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GEMINI_MODEL, model.trim()).apply()
    }

    fun isConfigured(context: Context): Boolean {
        return getApiKey(context).isNotEmpty()
    }

    /**
     * Executes prompt via Cloud LLM with full command-execution tool parsing.
     */
    fun generate(
        context: Context,
        prompt: String,
        systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,
        onChunk: ((String) -> Unit)? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = getApiKey(context)
        val model = getModel(context)

        executor.execute {
            // 1. Direct Google Gemini API call
            if (apiKey.isNotEmpty()) {
                val directResult = queryGeminiApi(apiKey, model, prompt, systemInstruction)
                if (directResult.isSuccess) {
                    val rawText = directResult.getOrNull() ?: ""
                    val executedText = handleEmbeddedCommands(context, rawText)
                    val cleaned = cleanForSpeech(executedText)
                    streamOutput(cleaned, onChunk)
                    onSuccess(cleaned)
                    return@execute
                } else {
                    Log.w(TAG, "Gemini API call failed: ${directResult.exceptionOrNull()?.message}, falling back to AGY...")
                }
            }

            // 2. Autonomous AGY CLI in PRoot Ubuntu (No API key needed)
            Log.i(TAG, "Executing via AGY CLI...")
            val agyRes = Shell.agy(prompt, timeoutMs = 45_000)
            if (agyRes.rc == 0 && agyRes.out.isNotBlank()) {
                val executedText = handleEmbeddedCommands(context, agyRes.out)
                val cleaned = cleanForSpeech(executedText)
                streamOutput(cleaned, onChunk)
                onSuccess(cleaned)
                return@execute
            }

            // 3. Fallback to AGY Daemon on port 5050
            queryAgyFallback(prompt,
                onSuccess = { agyResponse ->
                    val executedText = handleEmbeddedCommands(context, agyResponse)
                    val cleaned = cleanForSpeech(executedText)
                    streamOutput(cleaned, onChunk)
                    onSuccess(cleaned)
                },
                onError = { agyErr ->
                    if (agyRes.out.isNotBlank()) {
                        val executedText = handleEmbeddedCommands(context, agyRes.out)
                        val cleaned = cleanForSpeech(executedText)
                        streamOutput(cleaned, onChunk)
                        onSuccess(cleaned)
                    } else {
                        val finalErr = if (agyRes.err.isNotBlank()) agyRes.err else agyErr
                        onError("Cloud & AGY engines unavailable: $finalErr")
                    }
                }
            )
        }
    }

    private fun streamOutput(text: String, onChunk: ((String) -> Unit)?) {
        if (onChunk != null) {
            val words = text.split(" ")
            for (w in words) {
                onChunk("$w ")
                try { Thread.sleep(12) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Inspects Cloud LLM response for executable shell commands or JSON action blocks.
     * Executes them and appends execution results.
     */
    fun handleEmbeddedCommands(context: Context, rawText: String): String {
        CanonicalToolRegistry.init(context)
        var resultText = rawText

        // Check for ```command ... ``` blocks
        val cmdRegex = Regex("```(?:command|bash|sh)?\\s*\\n([\\s\\S]*?)\\n```")
        val cmdMatch = cmdRegex.find(rawText)
        if (cmdMatch != null) {
            val cmd = cmdMatch.groupValues[1].trim()
            if (cmd.isNotBlank()) {
                Log.i(TAG, "Executing Cloud LLM shell command: $cmd")
                val shellRes = Shell.root(cmd, timeoutMs = 15_000)
                val out = if (shellRes.out.isNotBlank()) shellRes.out.trim() else if (shellRes.err.isNotBlank()) shellRes.err.trim() else "Command executed successfully."
                resultText = rawText.replace(cmdMatch.value, "").trim() + "\n\nExecution Result:\n$out"
            }
        }

        // Check for ```action ... ``` JSON tool blocks
        val actionRegex = Regex("```action\\s*\\n([\\s\\S]*?)\\n```")
        val actionMatch = actionRegex.find(rawText)
        if (actionMatch != null) {
            try {
                val json = JSONObject(actionMatch.groupValues[1].trim())
                val intent = json.optString("intent", "")
                val args = json.optJSONObject("arguments") ?: JSONObject()
                if (intent.isNotBlank()) {
                    Log.i(TAG, "Executing Cloud LLM action: $intent with args: $args")
                    val toolRes = CanonicalToolRegistry.execute(context, intent, args)
                    val out = if (toolRes.success) "Executed $intent successfully." else "Action result: ${toolRes.error?.message ?: toolRes.status}"
                    resultText = rawText.replace(actionMatch.value, "").trim() + "\n\n$out"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing action block: ${e.message}")
            }
        }

        return resultText
    }

    /**
     * Performs a direct HTTPS request to Google Generative Language API.
     */
    private fun queryGeminiApi(
        apiKey: String,
        model: String,
        prompt: String,
        systemInstruction: String
    ): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val root = JSONObject()
            
            // System instructions
            if (systemInstruction.isNotEmpty()) {
                val sysPart = JSONObject().put("text", systemInstruction)
                val sysParts = JSONArray().put(sysPart)
                val sysContent = JSONObject().put("parts", sysParts)
                root.put("systemInstruction", sysContent)
            }

            // Contents
            val contentsArr = JSONArray()
            val userContent = JSONObject().apply {
                put("role", "user")
                val parts = JSONArray().put(JSONObject().put("text", prompt))
                put("parts", parts)
            }
            contentsArr.put(userContent)
            root.put("contents", contentsArr)

            // Generation config
            val genConfig = JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 1024)
            }
            root.put("generationConfig", genConfig)

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(root.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val responseText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val respJson = JSONObject(responseText)
                val candidates = respJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "")
                        return Result.success(text.trim())
                    }
                }
                Result.failure(Exception("Empty candidate response from Gemini API"))
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                Log.e(TAG, "Gemini API error ($code): $errText")
                Result.failure(Exception("Gemini API returned code $code: $errText"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception contacting Gemini API: ${e.message}", e)
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun queryAgyFallback(
        prompt: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val agyClient = AgyClient()
        agyClient.checkHealth(
            onSuccess = { health ->
                if (health.running) {
                    agyClient.sendPromptStream(
                        prompt = prompt,
                        onToken = {},
                        onStep = {},
                        onComplete = { fullText ->
                            if (fullText.isNotBlank()) {
                                onSuccess(fullText)
                            } else {
                                onError("Empty response from AGY")
                            }
                        },
                        onError = { err ->
                            onError(err)
                        }
                    )
                } else {
                    onError("AGY daemon is not running")
                }
            },
            onError = { err ->
                onError(err)
            }
        )
    }

    /**
     * Cleans text to be natural when spoken via Text-To-Speech (TTS).
     */
    fun cleanForSpeech(raw: String): String {
        val filteredLines = raw.lines().filterNot { line ->
            val l = line.trim().lowercase()
            l.contains("cannot create /root") ||
            l.contains("gemini.md") ||
            (l.contains("permission denied") && l.contains("root/")) ||
            l.contains("export path=") ||
            l.startsWith("export prefix=")
        }
        val text = filteredLines.joinToString("\n")
        return text
            .replace(Regex("```[a-zA-Z]*"), "") // Code fences
            .replace("```", "")
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1") // Bold **text**
            .replace(Regex("\\*(.*?)\\*"), "$1")       // Italic *text*
            .replace(Regex("`([^`]+)`"), "$1")        // Inline code `text`
            .replace(Regex("#{1,6}\\s*"), "")         // Headers # Header
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1") // Links [text](url)
            .replace(Regex("https?://\\S+"), "")     // Raw URLs
            .replace(Regex("[-*•]\\s+"), "")          // List bullets
            .replace(Regex("\\n{2,}"), "\n")          // Double newlines
            .trim()
    }
}
