package com.pr4nav.jarvis.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Ollama Cloud provider (https://ollama.com/api).
 * No download required: Bearer API key, OpenAI-style chat.
 *
 * - Models listed live via GET /api/tags (names like "gemma4:31b").
 * - Chat via POST /api/chat {model, messages, stream:false} → message.content.
 */
object OllamaClient {

    private const val TAG = "OllamaClient"
    private const val PREFS_NAME = "jarvis_ollama_prefs"
    const val KEY_API_KEY = "ollama_api_key"
    const val KEY_MODEL = "ollama_model"

    const val OLLAMA_CHAT_ENDPOINT = "https://ollama.com/api/chat"
    const val OLLAMA_TAGS_ENDPOINT = "https://ollama.com/api/tags"

    const val DEFAULT_MODEL = "gpt-oss:120b"

    private val executor = Executors.newCachedThreadPool()

    data class OllamaResponse(
        val success: Boolean,
        val response: String,
        val thinkingTrace: String = "",
        val modelUsed: String = DEFAULT_MODEL,
        val latencyMs: Long = 0L,
        val error: String? = null
    )

    private fun getPrefs(context: Context?): SharedPreferences? {
        return try {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    fun getApiKey(context: Context?): String {
        if (context == null) return ""
        return getPrefs(context)?.getString(KEY_API_KEY, "")?.trim() ?: ""
    }

    fun setApiKey(context: Context, apiKey: String) {
        getPrefs(context)?.edit()?.putString(KEY_API_KEY, apiKey.trim())?.apply()
    }

    fun getModel(context: Context?): String {
        if (context == null) return DEFAULT_MODEL
        val saved = getPrefs(context)?.getString(KEY_MODEL, null)
        return if (saved.isNullOrBlank()) DEFAULT_MODEL else saved
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context)?.edit()?.putString(KEY_MODEL, model.trim())?.apply()
    }

    // ── Pure parsers (unit-tested) ────────────────────────────────────────────

    /** Parses GET /api/tags → model names. */
    fun parseTagsResponse(raw: String): List<String> {
        val out = mutableListOf<String>()
        try {
            val arr = JSONObject(raw).optJSONArray("models") ?: return out
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                if (name.isNotBlank() && !name.equals("null", ignoreCase = true)) out.add(name)
            }
        } catch (_: Exception) { }
        return out
    }

    /** Parses POST /api/chat → (content, thinking). */
    fun parseChatResponse(raw: String): Pair<String, String> {
        try {
            val msg = JSONObject(raw).optJSONObject("message") ?: return "" to ""
            val content = msg.optString("content", "").trim()
            val thinking = msg.optString("thinking", "").trim()
                .ifBlank { msg.optString("reasoning_content", "").trim() }
            return content to thinking
        } catch (_: Exception) {
            return "" to ""
        }
    }

    // ── Network ───────────────────────────────────────────────────────────────

    @Volatile private var tagsCache: List<String> = emptyList()
    @Volatile private var tagsCacheAt: Long = 0L

    fun fetchModels(
        context: Context,
        onSuccess: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (tagsCache.isNotEmpty() && System.currentTimeMillis() - tagsCacheAt < 5 * 60 * 1000L) {
            onSuccess(tagsCache)
            return
        }
        executor.execute {
            try {
                val apiKey = getApiKey(context)
                val conn = (URL(OLLAMA_TAGS_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 15_000
                    if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("User-Agent", "JARVIS-Android/1.2")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    } catch (_: Exception) {
                        "HTTP $code"
                    }
                    onError("HTTP $code: ${err.take(200)}")
                    return@execute
                }
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                val models = parseTagsResponse(raw)
                if (models.isEmpty()) {
                    onError("No models listed by Ollama Cloud")
                    return@execute
                }
                tagsCache = models
                tagsCacheAt = System.currentTimeMillis()
                onSuccess(models)
            } catch (e: Exception) {
                onError(e.message ?: "Network error")
            }
        }
    }

    /**
     * Plain chat turn (no tools v1). History as (role, text) pairs.
     */
    fun query(
        context: Context,
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        preferredModel: String? = null,
        onSuccess: (OllamaResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            onError("Ollama API key not configured. Add it in Provider keys.")
            return
        }
        executor.execute {
            val t0 = System.currentTimeMillis()
            val model = preferredModel?.trim()?.takeIf { it.isNotBlank() } ?: getModel(context)
            try {
                val msgs = org.json.JSONArray()
                for ((role, text) in history.takeLast(20)) {
                    val r = if (role.lowercase() == "assistant") "assistant" else "user"
                    if (text.isNotBlank()) {
                        msgs.put(JSONObject().put("role", r).put("content", text.take(2000)))
                    }
                }
                msgs.put(JSONObject().put("role", "user").put("content", prompt))
                val payload = JSONObject().apply {
                    put("model", model)
                    put("messages", msgs)
                    put("stream", false)
                }
                val conn = (URL(OLLAMA_CHAT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("User-Agent", "JARVIS-Android/1.2")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    } catch (_: Exception) {
                        "HTTP $code"
                    }
                    Log.w(TAG, "Ollama chat HTTP $code: ${err.take(200)}")
                    onError("HTTP $code: ${err.take(300)}")
                    return@execute
                }
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                val (content, thinking) = parseChatResponse(raw)
                if (content.isBlank() || content.equals("null", ignoreCase = true)) {
                    onError("Ollama returned an empty reply")
                    return@execute
                }
                onSuccess(
                    OllamaResponse(
                        success = true,
                        response = content,
                        thinkingTrace = thinking,
                        modelUsed = model,
                        latencyMs = System.currentTimeMillis() - t0
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Ollama query failed: ${e.message}")
                onError(e.message ?: "Network error")
            }
        }
    }
}
