package com.pr4nav.jarvis.llm

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.agy.AgyClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cloud LLM Provider for JARVIS.
 * Directly integrates with Google Gemini API via HTTPS (no external dependencies required)
 * and falls back to local AGY daemon (:5050) if configured.
 */
object GeminiCloudLLM {

    private const val TAG = "GeminiCloudLLM"
    private const val PREFS_NAME = "jarvis_cloud_prefs"
    const val KEY_GEMINI_API_KEY = "gemini_api_key"
    const val KEY_GEMINI_MODEL = "gemini_model"
    const val DEFAULT_MODEL = "gemini-2.0-flash"

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
     * Queries Google Gemini API asynchronously.
     * If the API key is missing or fails, attempts AGY server fallback.
     */
    fun generate(
        context: Context,
        prompt: String,
        systemInstruction: String = "You are JARVIS, a helpful, intelligent personal AI companion. Keep answers clear, direct, and concise (under 3-4 sentences when possible), suitable for natural spoken voice dialogue. Do not use Markdown formatting or symbols like asterisks in your responses.",
        onChunk: ((String) -> Unit)? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = getApiKey(context)
        val model = getModel(context)

        executor.execute {
            // If Gemini API Key is configured, try direct cloud API for lowest latency
            if (apiKey.isNotEmpty()) {
                val directResult = queryGeminiApi(apiKey, model, prompt, systemInstruction)
                if (directResult.isSuccess) {
                    val rawText = directResult.getOrNull() ?: ""
                    val cleaned = cleanForSpeech(rawText)
                    if (onChunk != null) {
                        val words = cleaned.split(" ")
                        for (w in words) {
                            onChunk("$w ")
                            try { Thread.sleep(12) } catch (_: Exception) {}
                        }
                    }
                    onSuccess(cleaned)
                    return@execute
                } else {
                    Log.w(TAG, "Gemini API call failed: ${directResult.exceptionOrNull()?.message}, falling back to AGY CLI...")
                }
            }

            // Fallback / Autonomous Mode: Execute via AGY CLI directly (no API key required)
            Log.i(TAG, "Executing via AGY CLI in Ubuntu proot...")
            val agyRes = com.pr4nav.jarvis.Shell.agy(prompt, timeoutMs = 45_000)
            if (agyRes.rc == 0 && agyRes.out.isNotBlank()) {
                val cleaned = cleanForSpeech(agyRes.out)
                if (onChunk != null) {
                    val words = cleaned.split(" ")
                    for (w in words) {
                        onChunk("$w ")
                        try { Thread.sleep(12) } catch (_: Exception) {}
                    }
                }
                onSuccess(cleaned)
                return@execute
            }

            // Secondary Fallback: Check if AGY daemon is active on port 5050
            queryAgyFallback(prompt,
                onSuccess = { agyResponse ->
                    val cleaned = cleanForSpeech(agyResponse)
                    if (onChunk != null) {
                        val words = cleaned.split(" ")
                        for (w in words) {
                            onChunk("$w ")
                            try { Thread.sleep(12) } catch (_: Exception) {}
                        }
                    }
                    onSuccess(cleaned)
                },
                onError = { agyErr ->
                    if (agyRes.out.isNotBlank()) {
                        val cleaned = cleanForSpeech(agyRes.out)
                        if (onChunk != null) {
                            val words = cleaned.split(" ")
                            for (w in words) {
                                onChunk("$w ")
                                try { Thread.sleep(12) } catch (_: Exception) {}
                            }
                        }
                        onSuccess(cleaned)
                    } else {
                        val finalErr = if (agyRes.err.isNotBlank()) agyRes.err else agyErr
                        onError("AGY intelligence engine unavailable: $finalErr")
                    }
                }
            )
        }
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

            // Build request payload
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
                put("maxOutputTokens", 512)
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

    /**
     * Fallback to local AGY Server if running.
     */
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
     * Removes asterisks, Markdown headers, code block delimiters, and URLs.
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
