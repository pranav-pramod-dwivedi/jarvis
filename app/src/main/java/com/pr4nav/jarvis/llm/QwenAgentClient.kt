package com.pr4nav.jarvis.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Client for the Python Qwen Shell Agent Server running on llama serve.
 * Connects to http://127.0.0.1:8081/chat (or configured agent endpoint).
 */
object QwenAgentClient {

    private const val TAG = "QwenAgentClient"
    private const val PREFS_NAME = "jarvis_qwen_agent_prefs"
    const val KEY_AGENT_URL = "qwen_agent_url"
    const val DEFAULT_AGENT_URL = "http://127.0.0.1:8081"

    private val executor = Executors.newCachedThreadPool()

    fun getAgentUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AGENT_URL, DEFAULT_AGENT_URL) ?: DEFAULT_AGENT_URL
    }

    fun setAgentUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AGENT_URL, url.trim()).apply()
    }

    data class QwenAgentResponse(
        val success: Boolean,
        val response: String,
        val toolCallsExecuted: List<ToolCallRecord> = emptyList(),
        val latencyMs: Long = 0L,
        val error: String? = null
    )

    data class ToolCallRecord(
        val iteration: Int,
        val command: String,
        val exitCode: Int,
        val output: String
    )

    fun query(
        context: Context,
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        onSuccess: (QwenAgentResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val baseUrl = getAgentUrl(context)
        val endpoint = "${baseUrl.rstrip('/')}/chat"

        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(endpoint)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 45_000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                val payload = JSONObject().apply {
                    put("message", prompt)
                    val historyArr = JSONArray()
                    for ((role, text) in history.takeLast(4)) {
                        historyArr.put(JSONObject().apply {
                            put("role", if (role.lowercase() == "assistant") "assistant" else "user")
                            put("content", text)
                        })
                    }
                    put("history", historyArr)
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val rawResp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val json = JSONObject(rawResp)
                    val success = json.optBoolean("success", true)
                    val respText = json.optString("response", "")
                    val latency = json.optLong("latency_ms", 0L)
                    val toolsArr = json.optJSONArray("tool_calls_executed")
                    val toolCalls = mutableListOf<ToolCallRecord>()
                    if (toolsArr != null) {
                        for (i in 0 until toolsArr.length()) {
                            val item = toolsArr.getJSONObject(i)
                            toolCalls.add(
                                ToolCallRecord(
                                    iteration = item.optInt("iteration", i + 1),
                                    command = item.optString("command", ""),
                                    exitCode = item.optInt("exit_code", 0),
                                    output = item.optString("output", "")
                                )
                            )
                        }
                    }

                    onSuccess(
                        QwenAgentResponse(
                            success = success,
                            response = respText,
                            toolCallsExecuted = toolCalls,
                            latencyMs = latency
                        )
                    )
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    onError("Qwen Agent returned error ($code): $err")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to Qwen Agent at $endpoint: ${e.message}")
                onError("Failed to connect to Qwen Agent Server ($baseUrl). Is llama serve and agent.py running? (${e.message})")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun String.rstrip(c: Char): String = if (endsWith(c)) dropLast(1) else this
}
