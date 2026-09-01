package com.pr4nav.jarvis.llm

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Intelligent Client for Qwen3.5-2B.
 * Connects seamlessly to either:
 * 1. Direct `llama serve` HTTP API (:8080 or :9931 /v1/chat/completions) with on-device tool loop.
 * 2. Python Shell Agent Server (:8081 /chat).
 */
object QwenAgentClient {

    private const val TAG = "QwenAgentClient"
    private const val PREFS_NAME = "jarvis_qwen_agent_prefs"
    const val KEY_AGENT_URL = "qwen_agent_url"
    const val DEFAULT_AGENT_URL = "http://127.0.0.1:8080"

    private val CANDIDATE_BASE_URLS = listOf(
        "http://127.0.0.1:8080",
        "http://127.0.0.1:8081",
        "http://127.0.0.1:9931",
        "http://localhost:8080",
        "http://localhost:8081",
        "http://10.0.2.2:8080"
    )

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
        val configuredUrl = getAgentUrl(context)
        val targetsToTry = mutableListOf<String>()
        targetsToTry.add(configuredUrl)
        for (c in CANDIDATE_BASE_URLS) {
            if (!targetsToTry.contains(c)) targetsToTry.add(c)
        }

        executor.execute {
            var lastError = "No connection"
            for (baseUrl in targetsToTry) {
                try {
                    // Try Python agent endpoint first if URL ends with 8081 or /chat
                    if (baseUrl.contains("8081")) {
                        val res = tryQueryPythonAgent(baseUrl, prompt, history)
                        if (res != null) {
                            onSuccess(res)
                            return@execute
                        }
                    }

                    // Try direct llama serve OpenAI completions endpoint
                    val directRes = tryQueryLlamaServe(context, baseUrl, prompt, history)
                    if (directRes != null) {
                        onSuccess(directRes)
                        return@execute
                    }
                } catch (e: Exception) {
                    lastError = "Connection error on $baseUrl: ${e.message}"
                    Log.d(TAG, lastError)
                }
            }

            onError("Could not reach `llama serve` on ${targetsToTry.take(3).joinToString(", ")}. ($lastError)")
        }
    }

    private fun tryQueryPythonAgent(
        baseUrl: String,
        prompt: String,
        history: List<Pair<String, String>>
    ): QwenAgentResponse? {
        val endpoint = "${baseUrl.rstrip('/')}/chat"
        var conn: HttpURLConnection? = null
        val t0 = System.currentTimeMillis()
        try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3_000
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
                val latency = json.optLong("latency_ms", System.currentTimeMillis() - t0)
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
                return QwenAgentResponse(
                    success = success,
                    response = respText,
                    toolCallsExecuted = toolCalls,
                    latencyMs = latency
                )
            }
        } catch (_: Exception) {
            // fallback to direct llama serve
        } finally {
            conn?.disconnect()
        }
        return null
    }

    private fun tryQueryLlamaServe(
        context: Context,
        baseUrl: String,
        userPrompt: String,
        history: List<Pair<String, String>>
    ): QwenAgentResponse? {
        val endpoint = "${baseUrl.rstrip('/')}/v1/chat/completions"
        val t0 = System.currentTimeMillis()
        val toolCalls = mutableListOf<ToolCallRecord>()

        val systemPrompt = """You are JARVIS, an autonomous AI assistant running on Qwen3.5-2B.
You have permission to run shell commands to inspect files, query the system, and execute tasks.
When you need to run a shell command, respond ONLY with a JSON object in this exact format:
{"tool": "shell", "command": "<your_command>"}
When answering conversational questions or when no command is needed, respond directly in natural language without JSON.
Keep answers concise, direct, and helpful."""

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((r, txt) in history.takeLast(4)) {
            messages.put(JSONObject().put("role", if (r.lowercase() == "assistant") "assistant" else "user").put("content", txt))
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))

        var finalAnswer = ""
        val modelName = fetchLlamaModelName(baseUrl) ?: "lmstudio-community/Qwen3.5-2B-GGUF:Q4_K_M"

        for (iteration in 1..4) {
            val payload = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.4)
                put("max_tokens", 512)
                put("stream", false)
            }

            var conn: HttpURLConnection? = null
            var replyContent = ""
            try {
                val url = URL(endpoint)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 90_000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    Log.w(TAG, "llama serve returned $code: $err")
                    return null
                }

                val rawResp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(rawResp)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val msgObj = choices.getJSONObject(0).optJSONObject("message")
                    replyContent = msgObj?.optString("content", "")?.trim() ?: ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "llama serve request failed: ${e.message}")
                return null
            } finally {
                conn?.disconnect()
            }

            if (replyContent.isBlank()) break

            // Check if model emitted a tool call
            val cmd = extractToolCommand(replyContent)
            if (cmd != null && cmd.isNotBlank()) {
                // Execute command via Shell.ubuntu / Shell.termux
                val execOut = Shell.ubuntu(cmd)
                val outText = if (execOut.out.isNotBlank()) execOut.out else if (execOut.err.isNotBlank()) execOut.err else "(Command completed)"
                val truncatedOut = if (outText.length > 2000) outText.take(2000) + "\n... [truncated]" else outText
                val exitCode = execOut.rc ?: 0

                toolCalls.add(
                    ToolCallRecord(
                        iteration = iteration,
                        command = cmd,
                        exitCode = exitCode,
                        output = truncatedOut
                    )
                )

                messages.put(JSONObject().put("role", "assistant").put("content", replyContent))
                messages.put(JSONObject().put("role", "user").put("content", "Command `$cmd` exited with code $exitCode.\nOutput:\n$truncatedOut\n\nNow provide your final answer to the user."))
            } else {
                finalAnswer = replyContent
                break
            }
        }

        if (finalAnswer.isBlank() && toolCalls.isNotEmpty()) {
            finalAnswer = "Executed: ${toolCalls.last().command}\n${toolCalls.last().output}"
        }

        if (finalAnswer.isNotBlank()) {
            return QwenAgentResponse(
                success = true,
                response = finalAnswer,
                toolCallsExecuted = toolCalls,
                latencyMs = System.currentTimeMillis() - t0
            )
        }

        return null
    }

    private fun extractToolCommand(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val j = JSONObject(trimmed)
                if (j.optString("tool") == "shell") {
                    return j.optString("command")
                }
            } catch (_: Exception) {}
        }
        val pattern = Pattern.compile("\\{\\s*\"tool\"\\s*:\\s*\"shell\"\\s*,\\s*\"command\"\\s*:\\s*\"(.*?)\"\\s*\\}", Pattern.DOTALL)
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        val mdPattern = Pattern.compile("```(?:json)?\\s*\\{\\s*\"tool\"\\s*:\\s*\"shell\"\\s*,\\s*\"command\"\\s*:\\s*\"(.*?)\"\\s*\\}\\s*```", Pattern.DOTALL)
        val mdMatcher = mdPattern.matcher(text)
        if (mdMatcher.find()) {
            return mdMatcher.group(1)
        }
        return null
    }

    private fun fetchLlamaModelName(baseUrl: String): String? {
        val endpoint = "${baseUrl.rstrip('/')}/v1/models"
        var conn: HttpURLConnection? = null
        try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 5_000
            }
            if (conn.responseCode in 200..299) {
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(raw)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val m = data.getJSONObject(0).optString("id", "")
                    if (m.isNotBlank()) return m
                }
            }
        } catch (_: Exception) {}
        finally {
            conn?.disconnect()
        }
        return null
    }

    private fun String.rstrip(c: Char): String = if (endsWith(c)) dropLast(1) else this
}
