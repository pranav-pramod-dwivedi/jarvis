package com.pr4nav.jarvis.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.context.ConversationalContext
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.capabilities.RootCapability
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * High-Performance Autonomous Client for Kira AI Platform (https://kiraai.vn/api/v1).
 *
 * Implements:
 * 1. Default primary chat model with 1M context support.
 * 2. Model cascade priority: glm-5.3-free -> mimo-v2.5-free -> qwen3.8-flash-free (glm > 2.5 > qwen).
 * 3. DeepSeek-Harness / Claude-Code autonomous loop:
 *    - Full authority to execute Android shell (`sh -c`) and root (`su -c`) commands.
 *    - File system access (read, write, list).
 *    - Device capabilities (350+ mobile assistant tools).
 *    - Interactive dynamic UI generation (JarvisBrowser).
 * 4. Dual-mode tool invocation:
 *    - Standard OpenAI-compatible structured `tool_calls`.
 *    - Direct JSON Action parser fallback (from PrivateAgent) for models returning command blocks.
 * 5. Multi-turn autonomous agent execution until task completion.
 */
object KiraClient {

    private const val TAG = "KiraClient"
    private const val PREFS_NAME = "jarvis_kira_prefs"
    const val KEY_KIRA_API_KEY = "kira_api_key"
    const val KEY_KIRA_MODEL = "kira_model"
    const val KEY_KIRA_SOLO_MODEL = "kira_solo_model" // true = use selected model only, no cascade

    // Model Constants
    const val MODEL_GLM_5_3_FREE = "glm-5.3-free"
    const val MODEL_MIMO_2_5_FREE = "mimo-v2.5-free"
    const val MODEL_QWEN_3_8_FLASH_FREE = "qwen3.8-flash-free"
    const val MODEL_GPT_5_6_SOL = "gpt-5.6-sol"
    const val MODEL_GPT_5_6_LUNA = "gpt-5.6-luna"
    const val MODEL_GPT_OSS_120B = "gpt-oss-120b"
    const val MODEL_KIRA_3_5_PRO = "kira-3.5-pro"
    const val MODEL_KIRA_3_5_FLASH = "kira-3.5-flash"
    const val MODEL_KIRA_2_5_PRO = "kira-2.5-pro"
    const val MODEL_KIRA_2_5_FLASH = "kira-2.5-flash"
    const val MODEL_KIRA_MINI = "kira-mini-1.0"

    // Default: Auto router picks the fastest-fit model per task.
    const val MODEL_AUTO = "auto"
    const val DEFAULT_MODEL = MODEL_AUTO
    const val KEY_AUTO_ROUTER = "kira_auto_router" // true = classify each task to mini/glm/qwen
    private const val KEY_LAT_STATS = "kira_lat_stats" // "model=lastMs,avgMs,count;…"

    // Fallback cascade order as specified: glm-5.3-free -> qwen3.8-flash-free -> kira-mini-1.0 -> mimo-v2.5-free
    val FREE_MODEL_CASCADE = listOf(
        MODEL_GLM_5_3_FREE,
        MODEL_QWEN_3_8_FLASH_FREE,
        MODEL_KIRA_MINI,
        MODEL_MIMO_2_5_FREE
    )

    const val KIRA_BASE_URL = "https://kiraai.vn/api/v1"
    private const val KIRA_CHAT_ENDPOINT = "https://kiraai.vn/api/v1/chat/completions"
    private const val KIRA_MODELS_ENDPOINT = "https://kiraai.vn/api/v1/models"

    const val MAX_COMPLETION_TOKENS = 16384
    const val MAX_AGENT_TURNS = 30
    const val MAX_SAFE_CONTEXT_CHARS = 3_200_000 // ~800,000 tokens safe 1M context ceiling

    private val executor = Executors.newCachedThreadPool()

    data class ToolCallRecord(
        val iteration: Int,
        val toolName: String,
        val command: String,
        val backend: String,
        val exitCode: Int,
        val output: String,
        val durationMs: Long = 0L,
        val verified: Boolean = true
    )

    data class KiraResponse(
        val success: Boolean,
        val response: String,
        val toolCallsExecuted: List<ToolCallRecord> = emptyList(),
        val latencyMs: Long = 0L,
        val error: String? = null,
        val thinkingTrace: String = "",
        val modelUsed: String = DEFAULT_MODEL,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0
    )

    private fun getPrefs(context: Context?): SharedPreferences? {
        return try {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    fun cleanApiKey(rawKey: String): String {
        var clean = rawKey.trim()
        if (clean.startsWith("Bearer ", ignoreCase = true)) {
            clean = clean.substring(7).trim()
        }
        return clean
    }

    fun getApiKey(context: Context?): String {
        if (context == null) return ""
        val saved = getPrefs(context)?.getString(KEY_KIRA_API_KEY, "")?.trim() ?: ""
        if (saved.isNotBlank()) return cleanApiKey(saved)
        val envKey = System.getenv("KIRA_API_KEY")?.trim() ?: ""
        return cleanApiKey(envKey)
    }

    fun setApiKey(context: Context, apiKey: String) {
        getPrefs(context)?.edit()?.putString(KEY_KIRA_API_KEY, cleanApiKey(apiKey))?.apply()
    }

    fun getModel(context: Context?): String {
        if (context == null) return DEFAULT_MODEL
        val saved = getPrefs(context)?.getString(KEY_KIRA_MODEL, null)
        if (saved.isNullOrBlank()) {
            return DEFAULT_MODEL
        }
        return saved
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context)?.edit()?.putString(KEY_KIRA_MODEL, model.trim())?.apply()
    }

    /** Solo mode: run only the selected model, never cascade to fallbacks. */
    fun isSoloModel(context: Context?): Boolean {
        if (context == null) return false
        return getPrefs(context)?.getBoolean(KEY_KIRA_SOLO_MODEL, false) ?: false
    }

    fun setSoloModel(context: Context, solo: Boolean) {
        getPrefs(context)?.edit()?.putBoolean(KEY_KIRA_SOLO_MODEL, solo)?.apply()
    }

    /** Auto-router master switch. Only acts when the selected model is "auto". */
    fun isAutoRouter(context: Context?): Boolean {
        if (context == null) return true
        return getPrefs(context)?.getBoolean(KEY_AUTO_ROUTER, true) ?: true
    }

    fun setAutoRouter(context: Context, enabled: Boolean) {
        getPrefs(context)?.edit()?.putBoolean(KEY_AUTO_ROUTER, enabled)?.apply()
    }

    /** Last model the auto-router actually picked (for pill display). */
    @Volatile private var lastAutoPick: String = MODEL_QWEN_3_8_FLASH_FREE

    fun lastAutoModel(): String = lastAutoPick

    // ── Latency stats (per-model last/avg/count, shown in the route harness) ──

    data class ModelStats(val lastMs: Long, val avgMs: Long, val count: Int)

    fun recordModelLatency(context: Context?, model: String, ms: Long) {
        if (context == null || model.isBlank() || ms < 0) return
        try {
            val prefs = getPrefs(context) ?: return
            val map = prefs.getString(KEY_LAT_STATS, "").orEmpty()
                .split(';').mapNotNull { entry ->
                    val kv = entry.split('=')
                    if (kv.size != 2) return@mapNotNull null
                    val v = kv[1].split(',')
                    if (v.size != 3) return@mapNotNull null
                    kv[0] to ModelStats(
                        v[0].toLongOrNull() ?: 0L,
                        v[1].toLongOrNull() ?: 0L,
                        v[2].toIntOrNull() ?: 0
                    )
                }.toMap().toMutableMap()
            val prev = map[model]
            val count = (prev?.count ?: 0) + 1
            val avg = if (prev == null || prev.count == 0) ms else (prev.avgMs * prev.count + ms) / count
            map[model] = ModelStats(ms, avg, count)
            // Cap stored models to keep prefs small.
            val trimmed = (map.entries.sortedByDescending { it.value.count }.take(12))
                .joinToString(";") { (k, s) -> "$k=${s.lastMs},${s.avgMs},${s.count}" }
            prefs.edit().putString(KEY_LAT_STATS, trimmed).apply()
        } catch (_: Exception) { }
    }

    fun getModelStats(context: Context?): Map<String, ModelStats> {
        if (context == null) return emptyMap()
        return try {
            getPrefs(context)?.getString(KEY_LAT_STATS, "").orEmpty()
                .split(';').mapNotNull { entry ->
                    val kv = entry.split('=')
                    if (kv.size != 2) return@mapNotNull null
                    val v = kv[1].split(',')
                    if (v.size != 3) return@mapNotNull null
                    kv[0] to ModelStats(
                        v[0].toLongOrNull() ?: 0L,
                        v[1].toLongOrNull() ?: 0L,
                        v[2].toIntOrNull() ?: 0
                    )
                }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun clearModelStats(context: Context?) {
        try {
            getPrefs(context)?.edit()?.remove(KEY_LAT_STATS)?.apply()
        } catch (_: Exception) { }
    }

    // ── Auto-router: latency-aware task classification ────────────────────────
    // casual  → kira-mini-1.0 (fastest, least reasoning)
    // code    → glm-5.3-free (deepest reasoning)
    // mid     → qwen3.8-flash-free (balanced default)

    private val CODE_SIGNALS = listOf(
        "```", "code", "bug", "debug", "error", "traceback", "exception", "stacktrace",
        "function", "class", "method", "compile", "build", "script", "program",
        "python", "kotlin", "java", "javascript", "typescript", "html", "css", "regex",
        "sql", "api", "json", "git", "refactor", "implement", "deploy", "terminal",
        "shell", "command", "execute", "gradle", "adb", "/sdcard", ".py", ".kt",
        ".js", ".html", "write file", "read file", "edit file", "create file",
        "fix ", "failing", "not working", "crash", "algorithm", "database"
    )

    private val CASUAL_PATTERNS = listOf(
        Regex("^(hi+|hey|hello|yo|namaste|ram ram)\\b[!.…]*$"),
        Regex("^(good\\s?(morning|night|evening|afternoon))\\b[!.…]*$"),
        Regex("^(thanks?|thank you|shukriya|dhanyavad)\\b[!.…]*$"),
        Regex("^(bye|goodbye|see you|alvida)\\b[!.…]*$"),
        Regex("^(ok|okay|haan|hmm|achha|accha)\\b[!.…]*$"),
        Regex("^(how are you|how's it going|what's up|aur batao|kya haal)\\b.*$"),
        Regex("^(who are you|your name|tum kaun)\\b.*$"),
        Regex(".*\\b(joke|funny|haha|lol|bored|entertain me|meme)\\b.*$")
    )

    private val QUESTION_WORDS = listOf(
        "what", "why", "how", "when", "where", "which", "who", "explain",
        "compare", "difference", "kya", "kaise", "kyun", "kaun"
    )

    fun classifyAutoModel(prompt: String): String {
        val q = prompt.trim()
        if (q.isEmpty()) return MODEL_KIRA_MINI
        val lower = q.lowercase()
        // 1. Code / build / debug tasks → deepest reasoning.
        if (CODE_SIGNALS.any { lower.contains(it) }) return MODEL_GLM_5_3_FREE
        // 2. Pure casual chit-chat → fastest model.
        if (CASUAL_PATTERNS.any { it.matches(lower) }) return MODEL_KIRA_MINI
        val words = lower.split(Regex("\\s+"))
        val hasQuestion = QUESTION_WORDS.any { w -> words.contains(w) }
        // 3. Very short non-question utterances → casual.
        if (!hasQuestion && q.length < 35 && words.size <= 6) return MODEL_KIRA_MINI
        // 4. Everything in between → balanced default.
        return MODEL_QWEN_3_8_FLASH_FREE
    }

    /** Resolves the effective start model: explicit pick wins, "auto" classifies. */
    fun resolveStartModel(context: Context?, prompt: String, preferredModel: String?): String {
        val requested = preferredModel?.trim()?.takeIf { it.isNotBlank() } ?: getModel(context)
        if (requested == MODEL_AUTO) {
            if (!isAutoRouter(context)) return MODEL_GLM_5_3_FREE
            val picked = classifyAutoModel(prompt)
            lastAutoPick = picked
            Log.i(TAG, "Auto-router → $picked for: \"${prompt.take(50)}\"")
            return picked
        }
        return requested
    }

    // ── Model Discovery & Reasoning Tiers ─────────────────────────────────────

    /** Built-in curated model catalog with reasoning tiers & descriptions. */
    data class ModelInfo(
        val id: String,
        val label: String,
        val tier: String,      // "FLAGSHIP", "REASONING", "FAST", "CODE", "OSS"
        val contextWindow: String,
        val description: String
    )

    val BUILTIN_MODELS: List<ModelInfo> = listOf(
        ModelInfo(MODEL_AUTO, "Auto Router", "FAST", "1M", "Routes each task: casual → Mini 1.0 · code → GLM 5.3 · mid → Qwen 3.8"),
        ModelInfo(MODEL_GLM_5_3_FREE, "GLM 5.3 Free", "REASONING", "1M", "Primary free flagship · deep chain-of-thought reasoning"),
        ModelInfo(MODEL_MIMO_2_5_FREE, "Mimo 2.5 Free", "REASONING", "128k", "Free reasoning cascade tier · strong logic"),
        ModelInfo(MODEL_QWEN_3_8_FLASH_FREE, "Qwen 3.8 Flash Free", "FAST", "128k", "Ultra-fast free cascade tier"),
        ModelInfo(MODEL_GPT_5_6_SOL, "GPT-5.6 Sol", "FLAGSHIP", "400k", "Premium flagship reasoning"),
        ModelInfo(MODEL_GPT_5_6_LUNA, "GPT-5.6 Luna", "FLAGSHIP", "400k", "Premium flagship balanced"),
        ModelInfo(MODEL_GPT_OSS_120B, "GPT-OSS 120B", "OSS", "128k", "Open-weight 120B powerhouse"),
        ModelInfo(MODEL_KIRA_3_5_PRO, "Kira 3.5 Pro", "REASONING", "200k", "Deep reasoning pro engine"),
        ModelInfo(MODEL_KIRA_3_5_FLASH, "Kira 3.5 Flash", "FAST", "200k", "High-throughput flash engine"),
        ModelInfo(MODEL_KIRA_2_5_PRO, "Kira 2.5 Pro", "REASONING", "128k", "Previous-gen reasoning pro"),
        ModelInfo(MODEL_KIRA_2_5_FLASH, "Kira 2.5 Flash", "FAST", "128k", "Previous-gen fast engine"),
        ModelInfo(MODEL_KIRA_MINI, "Kira Mini 1.0", "FAST", "64k", "Lightweight rapid responder")
    )

    /** Models with very high reasoning (extended thinking traces). */
    private val HIGH_REASONING_PATTERNS = listOf(
        "glm-5", "glm-4", "glm-3", "mimo", "deepseek-r", "o1", "o3", "o4",
        "kira-3.5-pro", "kira-2.5-pro", "gpt-5.6", "claude"
    )

    fun isHighReasoning(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return HIGH_REASONING_PATTERNS.any { lower.contains(it) }
    }

    fun modelLabel(modelId: String): String {
        return BUILTIN_MODELS.firstOrNull { it.id == modelId }?.label
            ?: modelId.split("-").joinToString(" ") { part ->
                if (part.isNotEmpty()) part[0].uppercaseChar() + part.substring(1) else part
            }
    }

    /**
     * Extracts reasoning traces ( thinking, <thought>, <reasoning>) output by thinking models.
     * Guaranteed to separate thinking from the final answer, even if unclosed or if answer is inside.
     */
    fun extractThinking(text: String): Pair<String, String> {
        var raw = text.trim()
        val thinkBlocks = mutableListOf<String>()

        // 1. Closed thinking blocks: <think>...</think>, <thought>...</thought>, <reasoning>...</reasoning>
        val closedRegex = Regex("(?i)<(think|thought|reasoning)>([\\s\\S]*?)</\\1>")
        val matches = closedRegex.findAll(raw).toList()
        for (m in matches) {
            val t = m.groupValues[2].trim()
            if (t.isNotBlank()) thinkBlocks.add(t)
        }
        raw = raw.replace(closedRegex, "").trim()

        // 2. Unclosed thinking block at start/end
        val unclosedRegex = Regex("(?i)<(think|thought|reasoning)>([\\s\\S]*)$")
        val unclosedMatch = unclosedRegex.find(raw)
        if (unclosedMatch != null) {
            val t = unclosedMatch.groupValues[2].trim()
            if (t.isNotBlank()) thinkBlocks.add(t)
            raw = raw.replace(unclosedRegex, "").trim()
        }

        // Clean stray closing tags
        raw = raw.replace(Regex("(?i)</(think|thought|reasoning)>"), "").trim()

        val thinkingTrace = thinkBlocks.filter { !it.equals("null", ignoreCase = true) }.joinToString("\n\n").trim()

        // 3. Fallback: If clean content is blank, extract the substantive conclusion from thinking
        var cleanContent = if (raw.equals("null", ignoreCase = true) || raw.equals("null null", ignoreCase = true)) "" else raw.trim()
        if (cleanContent.isBlank() && thinkingTrace.isNotBlank() && !thinkingTrace.equals("null", ignoreCase = true)) {
            val paragraphs = thinkingTrace.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            val candidate = paragraphs.lastOrNull { p ->
                !p.startsWith("Let's", ignoreCase = true) &&
                !p.startsWith("I need to", ignoreCase = true) &&
                !p.startsWith("First,", ignoreCase = true) &&
                !p.startsWith("Thinking Process:", ignoreCase = true) &&
                !p.equals("null", ignoreCase = true)
            } ?: paragraphs.lastOrNull() ?: ""
            cleanContent = if (candidate.equals("null", ignoreCase = true)) "" else candidate
        }

        return Pair(thinkingTrace, cleanContent)
    }

    /**
     * Parses JSON Action blocks (PrivateAgent pattern) for models outputting direct command blocks.
     */
    fun parseAgentAction(response: String): JSONObject? {
        val all = parseAllAgentActions(response)
        return all.firstOrNull()
    }

    /**
     * Parses all JSON Action blocks for models executing multiple commands in sequence.
     */
    fun parseAllAgentActions(response: String): List<JSONObject> {
        val actions = mutableListOf<JSONObject>()
        try {
            // Check for ```json ... ``` blocks
            val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
            val codeMatches = codeBlockRegex.findAll(response).toList()
            for (cm in codeMatches) {
                val block = cm.groupValues[1].trim()
                val parsed = parseSingleJsonAction(block)
                if (parsed != null) actions.add(parsed)
            }

            if (actions.isEmpty()) {
                // Parse curly-brace JSON objects directly in text
                var searchIdx = 0
                while (searchIdx < response.length) {
                    val start = response.indexOf('{', searchIdx)
                    if (start < 0) break
                    var depth = 0
                    var end = -1
                    var inString = false
                    var escape = false
                    for (i in start until response.length) {
                        val c = response[i]
                        if (escape) {
                            escape = false
                            continue
                        }
                        if (c == '\\') {
                            escape = true
                            continue
                        }
                        if (c == '"') {
                            inString = !inString
                            continue
                        }
                        if (!inString) {
                            if (c == '{') depth++
                            else if (c == '}') {
                                depth--
                                if (depth == 0) {
                                    end = i
                                    break
                                }
                            }
                        }
                    }

                    if (end > start) {
                        val candidate = response.substring(start, end + 1)
                        val parsed = parseSingleJsonAction(candidate)
                        if (parsed != null) {
                            actions.add(parsed)
                        }
                        searchIdx = end + 1
                    } else {
                        searchIdx = start + 1
                    }
                }
            }
        } catch (_: Exception) {}

        return actions
    }

    private fun parseSingleJsonAction(jsonCandidate: String): JSONObject? {
        try {
            var trimmed = jsonCandidate.trim()
            if (trimmed.startsWith("```json")) trimmed = trimmed.removePrefix("```json").trim()
            if (trimmed.startsWith("```")) trimmed = trimmed.removePrefix("```").trim()
            if (trimmed.endsWith("```")) trimmed = trimmed.removeSuffix("```").trim()

            val firstBrace = trimmed.indexOf('{')
            val lastBrace = trimmed.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                val obj = JSONObject(trimmed.substring(firstBrace, lastBrace + 1))
                if (obj.has("action") || obj.has("command") || obj.has("tool") || obj.has("commands")) {
                    return obj
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Safely prunes older conversation turns (sliding window) to strictly obey the safe
     * 1M token character ceiling without ever dropping the system prompt (index 0)
     * or the active turn's user prompt/tool calls.
     */
    fun pruneContextSafely(messages: JSONArray, maxChars: Int = MAX_SAFE_CONTEXT_CHARS) {
        var totalChars = 0
        for (i in 0 until messages.length()) {
            val obj = messages.optJSONObject(i)
            totalChars += (obj?.optString("content")?.length ?: 0)
        }

        // If over safe ceiling, remove oldest conversational turns (index 1) until within limit
        while (totalChars > maxChars && messages.length() > 3) {
            val removed = messages.remove(1) as? JSONObject
            totalChars -= (removed?.optString("content")?.length ?: 0)
        }
    }

    /**
     * Dynamically fetches available models from Kira AI platform (/models endpoint).
     */
    fun fetchAvailableModels(
        context: Context,
        apiKeyOverride: String? = null,
        onSuccess: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = cleanApiKey(apiKeyOverride?.takeIf { it.isNotBlank() } ?: getApiKey(context))
        if (apiKey.isBlank()) {
            onError("Kira API key is empty")
            return
        }

        executor.execute {
            try {
                val conn = (URL(KIRA_MODELS_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    onError("Failed to fetch Kira models ($code): $err")
                    return@execute
                }

                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(raw)
                val modelsList = mutableListOf<String>()
                val dataArr = json.optJSONArray("data")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val mObj = dataArr.optJSONObject(i)
                        val id = mObj?.optString("id", "") ?: ""
                        if (id.isNotBlank()) {
                            modelsList.add(id)
                        }
                    }
                }
                // Ensure free models are always in the list
                for (m in FREE_MODEL_CASCADE) {
                    if (!modelsList.contains(m)) modelsList.add(0, m)
                }
                onSuccess(modelsList)
            } catch (e: Exception) {
                onError("Network error fetching Kira models: ${e.message}")
            }
        }
    }

    /**
     * Builds tools schema following OpenAI function calling specification.
     * Equips Kira models with full device shell, root su, file operations, and system capabilities.
     */
    fun buildJarvisToolsSchema(): JSONArray {
        val arr = JSONArray()

        // 1. execute_shell_command (Native Android shell via sh)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "execute_shell_command")
                put("description", "Executes any shell command directly on the Android OS via sh -c. Full authority: getprop, pm, am, ps, top, ls, df, cat, curl, etc.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "The shell command to execute on device")
                        })
                    })
                    put("required", JSONArray().put("command"))
                })
            })
        })

        // 2. execute_root_command (Superuser su access if rooted)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "execute_root_command")
                put("description", "Executes command with superuser / root (su) privileges. Has full system control, file modification, service management, and iptables.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "Root command to execute via su -c")
                        })
                    })
                    put("required", JSONArray().put("command"))
                })
            })
        })

        // 3. execute_batch_commands (Autonomous sequential multi-bash execution)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "execute_batch_commands")
                put("description", "Autonomous Batch Shell: Executes a sequence of multiple bash commands sequentially on Android OS via sh -c. Returns full stdout, stderr, and exit code for each individual command in one call. Use for multi-step diagnostics, setup, builds, and scripts.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("commands", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().put("type", "string"))
                            put("description", "Array of bash commands to execute sequentially in order")
                        })
                        put("commands_script", JSONObject().apply {
                            put("type", "string")
                            put("description", "Optional multiline shell script to execute if commands array is not used")
                        })
                        put("stop_on_error", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Whether to halt execution if any command in the sequence fails (default false for autonomous resilience)")
                        })
                    })
                    put("required", JSONArray().put("commands"))
                })
            })
        })

        // 4. read_file
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "read_file")
                put("description", "Reads the text contents of a file on storage or app filesystem.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path to the file to read")
                        })
                    })
                    put("required", JSONArray().put("path"))
                })
            })
        })

        // 4. write_file
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "write_file")
                put("description", "Writes text content to a file on storage or app filesystem.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path of file to write")
                        })
                        put("content", JSONObject().apply {
                            put("type", "string")
                            put("description", "Content to write into the file")
                        })
                    })
                    put("required", JSONArray().put("path").put("content"))
                })
            })
        })

        // 5. list_directory
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "list_directory")
                put("description", "Lists files and subdirectories at the given path.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Directory path to list")
                        })
                    })
                    put("required", JSONArray().put("path"))
                })
            })
        })

        // 6. execute_device_tool (Universal Assistant / Siri tool bridge)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "execute_device_tool")
                put("description", "Executes any of JARVIS's 350+ mobile assistant capabilities: system_torch, system_volume, system_battery, open_app, close_app, clock_alarm_set, calendar_event_create, phone_call_contact, message_send_sms, etc.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("tool_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "The specific tool name to execute")
                        })
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("description", "Arguments/parameters for the tool")
                        })
                    })
                    put("required", JSONArray().put("tool_name").put("parameters"))
                })
            })
        })

        // 7. browser_render_app (JarvisBrowser dynamic on-demand UI)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "browser_render_app")
                put("description", "Renders an interactive, dynamic HTML/CSS/JS mini web-app in JarvisBrowser surface for simulations, interactive charts, and tools.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("app_id", JSONObject().apply {
                            put("type", "string")
                            put("description", "Unique alphanumeric identifier for the app")
                        })
                        put("title", JSONObject().apply {
                            put("type", "string")
                            put("description", "Title for the app header")
                        })
                        put("html", JSONObject().apply {
                            put("type", "string")
                            put("description", "Complete self-contained HTML5 code (with styles and scripts)")
                        })
                    })
                    put("required", JSONArray().put("app_id").put("title").put("html"))
                })
            })
        })

        // 8. edit_file (precision text replacement)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "edit_file")
                put("description", "Replaces target_content with replacement_content inside a file.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Absolute path to file to edit")
                        })
                        put("target_content", JSONObject().apply {
                            put("type", "string")
                            put("description", "Exact substring in file to replace")
                        })
                        put("replacement_content", JSONObject().apply {
                            put("type", "string")
                            put("description", "New replacement content")
                        })
                    })
                    put("required", JSONArray().put("path").put("target_content").put("replacement_content"))
                })
            })
        })

        // 9. grep_search (file content search)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "grep_search")
                put("description", "Searches file contents for exact pattern or substring.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Directory or file path to search")
                        })
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "Text pattern to search for")
                        })
                    })
                    put("required", JSONArray().put("path").put("query"))
                })
            })
        })

        // 10. find_files (filename search)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "find_files")
                put("description", "Finds files matching wildcard name pattern within a directory.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Directory path to search")
                        })
                        put("pattern", JSONObject().apply {
                            put("type", "string")
                            put("description", "Wildcard filename pattern, e.g. *.kt or *log*")
                        })
                    })
                    put("required", JSONArray().put("path").put("pattern"))
                })
            })
        })

        // 11. remember (search persistent session memory & topic tables)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "remember")
                put("description", "Searches JARVIS's persistent memory and historical session archive for past topics, decisions, JEE study plans, and discussion tables.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "Search query or keyword (e.g. 'jee', 'physics', 'math', 'schedule', 'last session')")
                        })
                        put("topic", JSONObject().apply {
                            put("type", "string")
                            put("description", "Optional topic filter (e.g. 'roadmap', 'routine', 'sessions')")
                        })
                    })
                })
            })
        })

        return arr
    }

    /**
     * Executes tool on device and returns execution outcome.
     */
    fun executeTool(
        context: Context,
        toolName: String,
        args: JSONObject
    ): ToolCallRecord {
        val t0 = System.currentTimeMillis()
        var backend = "ANDROID_SHELL"
        var command = ""
        var exitCode = 0
        var output = ""
        var success = false

        when (toolName) {
            "remember", "search_context", "query_memory" -> {
                backend = "SESSION_MEMORY"
                val q = args.optString("query", args.optString("q", "")).trim()
                val t = args.optString("topic", "").trim()
                command = "remember: \"$q\" ${if (t.isNotBlank()) "[$t]" else ""}"
                val res = com.pr4nav.jarvis.session.JarvisSessionContextArchive.searchContext(context, q, t)
                output = res
                success = true
                exitCode = 0
            }
            "execute_shell_command", "run_shell_command", "execute_termux_command", "execute_android_command", "run_command" -> {
                command = args.optString("command", args.optString("cmd")).trim()
                backend = "SHELL"
                val res = Shell.local(command, 25_000L)
                exitCode = res.rc ?: (if (res.timedOut) 124 else 1)
                output = if (res.out.isNotBlank()) res.out else if (res.err.isNotBlank()) res.err else "(No output)"
                success = res.rc == 0
            }
            "execute_root_command", "run_root_command", "su" -> {
                command = args.optString("command", args.optString("cmd")).trim()
                backend = "ROOT_SU"
                val res = Shell.root(command, 35_000L)
                exitCode = res.rc ?: (if (res.timedOut) 124 else 1)
                output = if (res.out.isNotBlank()) res.out else if (res.err.isNotBlank()) res.err else "(No output)"
                success = res.rc == 0
            }
            "execute_batch_commands", "batch_shell_commands", "run_commands", "execute_commands", "batch_commands" -> {
                backend = "BATCH_SHELL"
                val cmdsList = mutableListOf<String>()
                val cmdArr = args.optJSONArray("commands")
                if (cmdArr != null) {
                    for (k in 0 until cmdArr.length()) {
                        val c = cmdArr.optString(k, "").trim()
                        if (c.isNotBlank()) cmdsList.add(c)
                    }
                }
                if (cmdsList.isEmpty()) {
                    val script = args.optString("commands_script", args.optString("command", "")).trim()
                    if (script.isNotBlank()) {
                        script.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach {
                            cmdsList.add(it)
                        }
                    }
                }

                command = "batch: ${cmdsList.size} commands (${cmdsList.take(2).joinToString("; ")}…)"
                val stopOnError = args.optBoolean("stop_on_error", false)
                val report = StringBuilder()
                var allSuccess = true
                var lastExitCode = 0

                if (cmdsList.isEmpty()) {
                    output = "No commands specified in batch."
                    success = false
                    exitCode = 1
                } else {
                    for ((idx, cmdItem) in cmdsList.withIndex()) {
                        val num = idx + 1
                        val res = Shell.local(cmdItem, 35_000L)
                        val code = res.rc ?: (if (res.timedOut) 124 else 1)
                        if (code != 0) {
                            allSuccess = false
                            lastExitCode = code
                        }
                        val out = if (res.out.isNotBlank()) res.out.trim() else if (res.err.isNotBlank()) res.err.trim() else "(No output)"
                        val truncatedOut = if (out.length > 20000) out.take(20000) + "\n... [truncated]" else out

                        report.append("[$num/${cmdsList.size}] $ $cmdItem\nExit: $code\n$truncatedOut\n\n")
                        if (stopOnError && code != 0) {
                            report.append("⚠️ Batch halted early due to non-zero exit code: $code\n")
                            break
                        }
                    }
                    output = report.toString().trim()
                    success = allSuccess
                    exitCode = lastExitCode
                }
            }
            "read_file" -> {
                val path = args.optString("path").trim()
                command = "read_file $path"
                backend = "FS"
                try {
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        val content = file.readText()
                        output = if (content.length > 100_000) content.take(100_000) + "\n... [truncated for 1M context]" else content
                        success = true
                        exitCode = 0
                    } else {
                        output = "File does not exist: $path"
                        success = false
                        exitCode = 1
                    }
                } catch (e: Exception) {
                    output = "Error reading file: ${e.message}"
                    success = false
                    exitCode = 1
                }
            }
            "write_file" -> {
                val path = args.optString("path").trim()
                val content = args.optString("content")
                command = "write_file $path"
                backend = "FS"
                try {
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    output = "Successfully wrote ${content.length} characters to $path"
                    success = true
                    exitCode = 0
                } catch (e: Exception) {
                    output = "Error writing file: ${e.message}"
                    success = false
                    exitCode = 1
                }
            }
            "list_directory" -> {
                val path = args.optString("path", "/sdcard").trim()
                command = "list_directory $path"
                backend = "FS"
                try {
                    val dir = File(path)
                    if (dir.exists() && dir.isDirectory) {
                        val files = dir.listFiles()?.take(50)?.joinToString("\n") {
                            "${if (it.isDirectory) "[DIR]" else "[FILE]"} ${it.name} (${it.length()}B)"
                        } ?: "(Empty directory)"
                        output = files
                        success = true
                        exitCode = 0
                    } else {
                        output = "Directory not found: $path"
                        success = false
                        exitCode = 1
                    }
                } catch (e: Exception) {
                    output = "Error listing directory: ${e.message}"
                    success = false
                    exitCode = 1
                }
            }
            "edit_file" -> {
                val path = args.optString("path").trim()
                val target = args.optString("target_content")
                val replacement = args.optString("replacement_content")
                command = "edit_file $path"
                backend = "FS"
                try {
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        val text = file.readText()
                        if (text.contains(target)) {
                            val newText = text.replace(target, replacement)
                            file.writeText(newText)
                            output = "Successfully edited $path"
                            success = true
                            exitCode = 0
                        } else {
                            output = "Target content not found in $path"
                            success = false
                            exitCode = 1
                        }
                    } else {
                        output = "File does not exist: $path"
                        success = false
                        exitCode = 1
                    }
                } catch (e: Exception) {
                    output = "Error editing file: ${e.message}"
                    success = false
                    exitCode = 1
                }
            }
            "grep_search" -> {
                val path = args.optString("path", "/sdcard").trim()
                val query = args.optString("query").trim()
                command = "grep_search $path \"$query\""
                backend = "FS"
                try {
                    val dir = File(path)
                    val matches = mutableListOf<String>()
                    if (dir.exists()) {
                        dir.walkTopDown().maxDepth(4).filter { it.isFile && it.length() < 500_000 }.forEach { f ->
                            try {
                                f.forEachLine { line ->
                                    if (line.contains(query, ignoreCase = true) && matches.size < 40) {
                                        matches.add("${f.name}: ${line.trim().take(120)}")
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        output = if (matches.isNotEmpty()) matches.joinToString("\n") else "No matches found for \"$query\" in $path"
                        success = true
                        exitCode = 0
                    } else {
                        output = "Path does not exist: $path"
                        success = false
                        exitCode = 1
                    }
                } catch (e: Exception) {
                    output = "Error during grep search: ${e.message}"
                    success = false
                    exitCode = 1
                }
            }
            "find_files" -> {
                val path = args.optString("path", "/sdcard").trim()
                val pattern = args.optString("pattern").trim()
                command = "find_files $path \"$pattern\""
                backend = "FS"
                try {
                    val dir = File(path)
                    val matches = mutableListOf<String>()
                    if (dir.exists()) {
                        val regex = Regex(pattern.replace("*", ".*"), RegexOption.IGNORE_CASE)
                        dir.walkTopDown().maxDepth(4).forEach { f ->
                            if (regex.containsMatchIn(f.name) && matches.size < 40) {
                                matches.add("${if (f.isDirectory) "[DIR]" else "[FILE]"} ${f.absolutePath}")
                            }
                        }
                        output = if (matches.isNotEmpty()) matches.joinToString("\n") else "No files matching \"$pattern\" in $path"
                        success = true
                        exitCode = 0
                    } else {
                        output = "Path does not exist: $path"
                        success = false
                        exitCode = 1
                    }
                } catch (e: Exception) {
                    output = "Error finding files: ${e.message}"
                    success = false
                    exitCode = 1
                }
            }
            "browser_render_app" -> {
                backend = "JARVIS_BROWSER"
                val appId = args.optString("app_id", "app-${System.currentTimeMillis()}").trim()
                val title = args.optString("title", "Jarvis Dynamic App").trim()
                val html = args.optString("html")
                command = "render_app: $title ($appId)"
                try {
                    val app = com.pr4nav.jarvis.browser.JarvisBrowserAppManager.createApp(
                        ctx = context,
                        appId = appId,
                        title = title,
                        description = "Autonomous UI generated by Kira AI",
                        html = html,
                        isTemporary = true
                    )
                    com.pr4nav.jarvis.browser.JarvisBrowserActivity.launch(context, app.id)
                    output = "Successfully launched dynamic UI in JarvisBrowser for $title ($appId)"
                    success = true
                    exitCode = 0
                } catch (e: Exception) {
                    output = "Error rendering JarvisBrowser app: ${e.message}"
                    success = false
                    exitCode = 1
                }
            }
            "execute_device_tool" -> {
                backend = "CANONICAL_TOOL"
                val innerToolName = args.optString("tool_name", "").trim()
                val innerArgs = args.optJSONObject("parameters") ?: JSONObject()
                command = "device_tool: $innerToolName"
                val toolRes = CanonicalToolRegistry.execute(context, innerToolName, innerArgs)
                success = toolRes.success
                exitCode = if (success) 0 else 1
                output = if (toolRes.data != null) toolRes.data.toString() else (toolRes.error?.message ?: toolRes.status.name)
            }
            else -> {
                backend = "CANONICAL_TOOL"
                command = toolName
                val toolRes = CanonicalToolRegistry.execute(context, toolName, args)
                success = toolRes.success
                exitCode = if (success) 0 else 1
                output = if (toolRes.data != null) toolRes.data.toString() else (toolRes.error?.message ?: toolRes.status.name)
            }
        }

        val dur = System.currentTimeMillis() - t0
        val truncated = if (output.length > 64_000) output.take(64_000) + "\n... [truncated for 1M context safety]" else output

        return ToolCallRecord(
            iteration = 0,
            toolName = toolName,
            command = command,
            backend = backend,
            exitCode = exitCode,
            output = truncated,
            durationMs = dur,
            verified = success
        )
    }

    /**
     * The entire system prompt: exactly 2 lines. All real context (profile,
     * environment, memory, skills, artifacts) travels separately as a
     * [CONTEXT PACKET] user message built by [buildContextPacket].
     */
    /**
     * For tools that materialize openable content, returns the id the chat UI
     * can use for an "Open" action (e.g. a browser app id).
     */
    private fun actionIdFor(toolName: String, args: JSONObject): String? {
        return when (toolName.trim().lowercase()) {
            "browser_render_app" -> args.optString("app_id", "").trim().takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * Registers freshly rendered mini-apps as durable artifacts and emits an
     * ArtifactSaved event so the chat can show an openable card + Artifacts tab entry.
     */
    private fun maybeEmitArtifact(
        context: Context,
        toolName: String,
        args: JSONObject,
        outcome: ToolCallRecord,
        onEvent: ((com.pr4nav.jarvis.chat.AgentStreamEvent) -> Unit)?
    ) {
        if (onEvent == null || !outcome.verified) return
        try {
            if (toolName.trim().lowercase() == "browser_render_app") {
                val appId = args.optString("app_id", "app-${System.currentTimeMillis()}").trim()
                val title = args.optString("title", "Jarvis App").trim()
                val html = args.optString("html", "")
                if (html.length >= 200) {
                    val record = com.pr4nav.jarvis.artifacts.JarvisArtifactManager.saveArtifact(
                        context = context,
                        id = appId,
                        title = title,
                        type = "UI_APP",
                        content = html,
                        fileExtension = "html",
                        summary = "Mini-app rendered by agent",
                        tags = listOf("browser", "mini-app")
                    )
                    onEvent(
                        com.pr4nav.jarvis.chat.AgentStreamEvent.ArtifactSaved(
                            id = record.id,
                            title = record.title,
                            type = record.type,
                            filePath = record.filePath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Artifact registration failed: ${e.message}")
        }
    }

    private fun buildSystemPrompt(context: Context, modelName: String = ""): String {
        val base = "You are JARVIS, an autonomous engineering & mobile-intelligence agent with full on-device authority (YOLO mode: never ask permission, self-heal every error, verify every action).\n" +
                "Follow the CONTEXT PACKET conventions and skills; close <think> tags and always finish with the complete, beautifully formatted user-facing answer."
        // Fast tiers think fast: cap internal reasoning so answers start immediately.
        // Deep tiers (GLM / pro) keep full chain-of-thought.
        val m = modelName.lowercase()
        val fastTier = m.contains("mini") || m.contains("flash") || m.contains("qwen") ||
            m.contains("mimo") || m == MODEL_AUTO
        return if (fastTier) {
            base + "\nSPEED RULE: reason FAST with LOW effort — at most 3 short thinking lines, then answer immediately. Never ramble internally."
        } else {
            base
        }
    }

    /**
     * Assembles the [CONTEXT PACKET]: user profile, environment status, memory
     * pointer, artifacts and matched skills — real context without system-prompt bloat.
     */
    private fun buildContextPacket(context: Context, prompt: String): String {
        val userName = try {
            com.pr4nav.jarvis.JarvisApp.instance?.let {
                com.pr4nav.jarvis.setup.SetupManager.getUserName(it)
            }
        } catch (_: Exception) { null } ?: ""
        val profile = buildString {
            val name = if (userName.isNotBlank() && userName != "JARVIS") userName else "Pranav"
            appendLine("The user's name is $name.")
            appendLine("19, JEE drop year (2026-27). Target: Jan 2027, backup Apr 2027.")
            appendLine("Strictly nocturnal: sleeps ~6 AM to ~1-3 PM, studies overnight. NEVER schedule on daytime assumptions.")
            appendLine("Talks in short lowercase bursts. Match him: terse, casual, no throat-clearing, no motivational filler. A dry, ultra-capable co-pilot.")
            appendLine("Introverted: he will ignore you sometimes. Be a human pin: persistent, direct, action-oriented.")
        }
        val rootState = if (com.pr4nav.jarvis.capabilities.RootCapability.state ==
            com.pr4nav.jarvis.capabilities.RootCapability.State.AVAILABLE
        ) "ROOT (su) AVAILABLE" else "ROOT NOT AVAILABLE (Standard Shell)"
        val model = try { getModel(context) } catch (_: Exception) { DEFAULT_MODEL }
        val cwd = try { com.pr4nav.jarvis.SessionState.dir } catch (_: Exception) { "/sdcard" }
        val env = listOf(
            "Device: Android OS",
            "Privilege: $rootState",
            "Model: $model",
            "Working dir: $cwd",
            "Context window: 1,000,000 tokens (ingest whole files, logs, structures freely)",
            "Tools: 350+ device capabilities, multi-bash, root su, file ops, JarvisBrowser mini-app engine (call tools via OpenAI tool_calls OR ```json {\"action\": ..., }``` blocks)"
        )
        val memory = "Past sessions, JEE roadmaps and topic tables are archived in /sdcard/jarvis_sessions_context.txt. " +
                "Use the `remember` tool or `read_file` to pull specifics on demand instead of guessing."
        return try {
            com.pr4nav.jarvis.context.SkillContextEngine.buildContextPacket(
                context = context,
                query = prompt,
                envLines = env,
                profileBlock = profile,
                memoryPointer = memory
            )
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Queries Kira AI with automatic model cascading (glm-5.3-free -> mimo-v2.5-free -> qwen3.8-flash-free).
     * Runs multi-turn DeepSeek Harness / Claude Code autonomous loop.
     */
    /** Hard ceiling per model attempt: a hung request can never stall a turn forever. */
    const val ATTEMPT_DEADLINE_SEC = 100L
    fun isQuotaError(msg: String?): Boolean {
        if (msg.isNullOrBlank()) return false
        val m = msg.lowercase()
        return m.contains("402") || m.contains("insufficient") || m.contains("quota") ||
            m.contains("balance") || m.contains("top up") || m.contains("topup") ||
            m.contains("vnd_balance_exhausted")
    }

    fun quotaHint(): String = "Kira wallet empty (0 VND) — top up at kiraai.vn to continue."

    fun query(
        context: Context,
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        preferredModel: String? = null,
        onStatus: ((String) -> Unit)? = null,
        onChunk: ((String) -> Unit)? = null,
        onSuccess: (KiraResponse) -> Unit,
        onError: (String) -> Unit,
        onEvent: ((com.pr4nav.jarvis.chat.AgentStreamEvent) -> Unit)? = null
    ) {
        val apiKey = cleanApiKey(getApiKey(context))
        if (apiKey.isBlank()) {
            onError("Kira API key not configured. Please add your Kira API key in Settings.")
            return
        }

        executor.execute {
            val t0 = System.currentTimeMillis()
            val startModel = resolveStartModel(context, prompt, preferredModel)

            // Cascade sequence: selected model first, then the remaining free models in order (glm > 2.5 > qwen)
            // Solo mode: selected model only, no fallback cascade.
            val cascadeList = mutableListOf<String>()
            cascadeList.add(startModel)
            if (!isSoloModel(context)) {
                for (m in FREE_MODEL_CASCADE) {
                    if (!cascadeList.contains(m)) cascadeList.add(m)
                }
            } else {
                Log.i(TAG, "Solo-model mode: using $startModel only, cascade disabled.")
            }

            var lastError = "Unknown error"
            var executedSuccessfully = false

            for (currentModel in cascadeList) {
                if (Thread.currentThread().isInterrupted) {
                    Log.i(TAG, "Query cancelled or interrupted; aborting model cascade.")
                    return@execute
                }
                onStatus?.invoke("Querying Kira AI ($currentModel)…")
                Log.i(TAG, "Attempting Kira AI model: $currentModel")

                val attemptT0 = System.currentTimeMillis()
                // Hard deadline: a hung socket can never stall a turn forever.
                val future = executor.submit<KiraResponse?> {
                    attemptQueryWithModel(
                        context = context,
                        apiKey = apiKey,
                        modelName = currentModel,
                        prompt = prompt,
                        history = history,
                        t0 = t0,
                        onStatus = onStatus,
                        onChunk = onChunk,
                        onEvent = onEvent
                    )
                }
                val res = try {
                    future.get(ATTEMPT_DEADLINE_SEC, TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    future.cancel(true)
                    Log.w(TAG, "Model $currentModel timed out after $ATTEMPT_DEADLINE_SEC s; escalating.")
                    KiraResponse(success = false, response = "",
                        error = "Model $currentModel timed out after ${ATTEMPT_DEADLINE_SEC}s", modelUsed = currentModel)
                } catch (e: Exception) {
                    KiraResponse(success = false, response = "",
                        error = e.message ?: "Attempt failed", modelUsed = currentModel)
                }
                if (res != null && res.success) {
                    recordModelLatency(context, currentModel, System.currentTimeMillis() - attemptT0)
                }

                if (res != null && res.success && res.response.isNotBlank() && !res.response.equals("null", ignoreCase = true)) {
                    executedSuccessfully = true
                    onSuccess(res)
                    return@execute
                } else {
                    val err = res?.error ?: "Model $currentModel yielded empty or invalid response"
                    if (isQuotaError(err)) {
                        // Wallet empty: every other Kira model will fail identically — skip the cascade.
                        lastError = quotaHint()
                        Log.w(TAG, "Quota exhausted; skipping remaining Kira cascade.")
                        onStatus?.invoke("Kira wallet empty — Top up at kiraai.vn. Trying next engine…")
                        break
                    }
                    lastError = err
                    Log.w(TAG, "Model $currentModel yielded no usable response ($err); escalating to next in cascade...")
                }
            }

            if (!executedSuccessfully) {
                onError("All Kira models in cascade failed. Last error: $lastError")
            }
        }
    }

    /**
     * Executes query with a specific Kira model across an autonomous multi-turn loop.
     */
    private fun attemptQueryWithModel(
        context: Context,
        apiKey: String,
        modelName: String,
        prompt: String,
        history: List<Pair<String, String>>,
        t0: Long,
        onStatus: ((String) -> Unit)?,
        onChunk: ((String) -> Unit)?,
        onEvent: ((com.pr4nav.jarvis.chat.AgentStreamEvent) -> Unit)? = null
    ): KiraResponse? {
        val toolRecords = mutableListOf<ToolCallRecord>()
        val messages = JSONArray()

        // 2-line system prompt. Everything else travels as a [CONTEXT PACKET] user message.
        val systemPrompt = buildSystemPrompt(context, modelName)
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))

        // Real context: deep history (128k–1M windows), context packet right before the prompt.
        val recentHistory = if (history.isNotEmpty()) history else ConversationalContext.getRecentTurns(220)
        for ((role, text) in recentHistory) {
            messages.put(
                JSONObject().apply {
                    put("role", if (role.lowercase(Locale.ROOT) == "assistant") "assistant" else "user")
                    put("content", text)
                }
            )
        }
        val contextPacket = buildContextPacket(context, prompt)
        if (contextPacket.isNotBlank()) {
            messages.put(JSONObject().put("role", "user").put("content", contextPacket))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        pruneContextSafely(messages, MAX_SAFE_CONTEXT_CHARS)

        var finalResponseText = ""
        var thinkingTrace = ""
        val executedToolSignatures = mutableListOf<String>()

        try {
            for (iteration in 1..MAX_AGENT_TURNS) {
                pruneContextSafely(messages, MAX_SAFE_CONTEXT_CHARS)

                val payload = JSONObject().apply {
                    put("model", modelName)
                    put("messages", messages)
                    put("max_tokens", MAX_COMPLETION_TOKENS)
                    put("temperature", 0.6)
                    put("tools", buildJarvisToolsSchema())
                    put("tool_choice", "auto")
                }

                val conn = (URL(KIRA_CHAT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 30_000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("User-Agent", "JARVIS-Android/1.2")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    Log.w(TAG, "Kira API HTTP error ($code) for $modelName: $err")
                    return KiraResponse(
                        success = false,
                        response = "",
                        error = "HTTP $code: $err",
                        modelUsed = modelName
                    )
                }

                val rawResponse = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(rawResponse)
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    return KiraResponse(success = false, response = "", error = "Empty choices array", modelUsed = modelName)
                }

                val messageObj = choices.getJSONObject(0).optJSONObject("message")
                val contentRaw = if (messageObj != null && !messageObj.isNull("content")) {
                    val raw = messageObj.opt("content")
                    if (raw == null || raw == JSONObject.NULL) "" else raw.toString().trim()
                } else ""
                val content = if (contentRaw.equals("null", ignoreCase = true) || contentRaw.equals("null null", ignoreCase = true)) "" else contentRaw

                val reasoningRaw = if (messageObj != null) {
                    var r = ""
                    for (k in listOf("reasoning_content", "reasoning", "thought")) {
                        if (!messageObj.isNull(k)) {
                            val v = messageObj.opt(k)
                            if (v != null && v != JSONObject.NULL) {
                                val str = v.toString().trim()
                                if (str.isNotBlank() && !str.equals("null", ignoreCase = true)) {
                                    r = str
                                    break
                                }
                            }
                        }
                    }
                    r
                } else ""
                val reasoningContent = reasoningRaw
                val toolCalls = messageObj?.optJSONArray("tool_calls")

                if (reasoningContent.isNotBlank()) {
                    if (!thinkingTrace.contains(reasoningContent)) {
                        thinkingTrace += (if (thinkingTrace.isNotBlank()) "\n\n" else "") + reasoningContent
                    }
                }

                val (thinkFromContent, cleanContent) = extractThinking(content)
                if (thinkFromContent.isNotBlank() && !thinkingTrace.contains(thinkFromContent)) {
                    thinkingTrace += (if (thinkingTrace.isNotBlank()) "\n\n" else "") + thinkFromContent
                }

                // 1. Stream thinking trace immediately as soon as generated
                val activeThinking = if (reasoningContent.isNotBlank() && !reasoningContent.equals("null", ignoreCase = true)) {
                    reasoningContent
                } else if (thinkFromContent.isNotBlank() && !thinkFromContent.equals("null", ignoreCase = true)) {
                    thinkFromContent
                } else ""

                if (activeThinking.isNotBlank()) {
                    val firstLine = activeThinking.lines().firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.trim()?.take(55)
                    if (!firstLine.isNullOrBlank() && !firstLine.equals("null", ignoreCase = true)) {
                        onStatus?.invoke("Thinking: $firstLine…")
                    }
                    if (onEvent == null) onChunk?.invoke("Thinking:\n${activeThinking.trim()}\n\n")
                    onEvent?.invoke(
                        com.pr4nav.jarvis.chat.AgentStreamEvent.ThinkingDelta(activeThinking.trim())
                    )
                }

                // Check for tool calling: 1) OpenAI tool_calls OR 2) PrivateAgent JSON Actions
                val hasToolCalls = toolCalls != null && toolCalls.length() > 0
                val parsedActions = if (!hasToolCalls) parseAllAgentActions(cleanContent) else emptyList()

                // 2. Stream partial comments between agent turns (only when tools are about to run)
                if (hasToolCalls || parsedActions.isNotEmpty()) {
                    if (cleanContent.isNotBlank() && !cleanContent.equals("null", ignoreCase = true) && !cleanContent.equals("null null", ignoreCase = true)) {
                        if (onEvent == null) onChunk?.invoke("$cleanContent\n\n")
                        onEvent?.invoke(
                            com.pr4nav.jarvis.chat.AgentStreamEvent.TextDelta(cleanContent)
                        )
                    }
                }

                if (!hasToolCalls && parsedActions.isEmpty()) {
                    // Turn finished — final answer reached
                    val answerCandidate = if (cleanContent.isNotBlank() && !cleanContent.equals("null", ignoreCase = true) && !cleanContent.equals("null null", ignoreCase = true)) {
                        cleanContent
                    } else if (thinkingTrace.isNotBlank() && !thinkingTrace.equals("null", ignoreCase = true)) {
                        val (_, fallbackText) = com.pr4nav.jarvis.response.UserResponseSanitizer.stripThinking(thinkingTrace)
                        if (fallbackText.isNotBlank() && !fallbackText.equals("null", ignoreCase = true) && !fallbackText.equals("null null", ignoreCase = true)) fallbackText else ""
                    } else ""

                    finalResponseText = if (answerCandidate.isNotBlank() && !answerCandidate.equals("null", ignoreCase = true)) {
                        answerCandidate
                    } else {
                        "Task executed successfully."
                    }
                    if (onEvent == null) onChunk?.invoke(finalResponseText)
                    onEvent?.invoke(
                        com.pr4nav.jarvis.chat.AgentStreamEvent.TextDelta(finalResponseText)
                    )
                    onEvent?.invoke(
                        com.pr4nav.jarvis.chat.AgentStreamEvent.ThinkingDone(System.currentTimeMillis() - t0)
                    )
                    onEvent?.invoke(
                        com.pr4nav.jarvis.chat.AgentStreamEvent.Final(
                            text = finalResponseText,
                            model = modelName,
                            latencyMs = System.currentTimeMillis() - t0,
                            handled = true,
                            thinkingTrace = thinkingTrace,
                            toolsUsed = toolRecords.size
                        )
                    )
                    break
                }

                // Handle OpenAI tool_calls
                if (hasToolCalls) {
                    messages.put(messageObj) // assistant message with tool_calls
                    var loopAborted = false

                    for (i in 0 until toolCalls!!.length()) {
                        val tc = toolCalls.getJSONObject(i)
                        val fn = tc.optJSONObject("function")
                        val tcId = tc.optString("id", "call_$i")
                        val fnName = fn?.optString("name", "") ?: ""
                        val fnArgsStr = fn?.optString("arguments", "{}") ?: "{}"
                        val fnArgs = try { JSONObject(fnArgsStr) } catch (_: Exception) { JSONObject() }

                        val toolSig = "$fnName:$fnArgsStr"
                        val repeats = executedToolSignatures.count { it == toolSig }
                        if (repeats >= 2) {
                            Log.w(TAG, "Infinite loop prevention: $fnName repeated $repeats times with identical args. Breaking.")
                            onStatus?.invoke("Breaking tool loop; finalizing answer…")
                            finalResponseText = if (toolRecords.isNotEmpty()) {
                                "Task execution finished. Autonomous actions executed:\n" + toolRecords.takeLast(4).joinToString("\n") { "• ${it.command} (${it.exitCode})" }
                            } else {
                                "Completed requested operations."
                            }
                            if (onEvent == null) onChunk?.invoke(finalResponseText)
                            onEvent?.invoke(
                                com.pr4nav.jarvis.chat.AgentStreamEvent.TextDelta(finalResponseText)
                            )
                            onEvent?.invoke(
                                com.pr4nav.jarvis.chat.AgentStreamEvent.Final(
                                    text = finalResponseText,
                                    model = modelName,
                                    latencyMs = System.currentTimeMillis() - t0,
                                    handled = true,
                                    thinkingTrace = thinkingTrace,
                                    toolsUsed = toolRecords.size
                                )
                            )
                            loopAborted = true
                            break
                        }
                        executedToolSignatures.add(toolSig)

                        val cmdPreview = fnArgs.optString("command", fnArgs.optString("commands", fnArgs.optString("path", fnArgs.optString("query", fnArgs.optString("tool_name", "")))))
                        onStatus?.invoke("Executing $fnName…")
                        if (onEvent == null) onChunk?.invoke("$fnName ${if (cmdPreview.isNotBlank()) cmdPreview else ""}\n")
                        onEvent?.invoke(
                            com.pr4nav.jarvis.chat.AgentStreamEvent.ToolStart(
                                tool = fnName,
                                label = "",
                                detail = cmdPreview
                            )
                        )

                        val outcome = executeTool(context, fnName, fnArgs)
                        val rec = outcome.copy(iteration = iteration)
                        toolRecords.add(rec)

                        val previewOut = if (outcome.output.length > 600) outcome.output.take(600) + "\n... [truncated]" else outcome.output
                        if (onEvent == null) onChunk?.invoke("$previewOut\n\n")
                        onEvent?.invoke(
                            com.pr4nav.jarvis.chat.AgentStreamEvent.ToolEnd(
                                tool = fnName,
                                label = "",
                                detail = rec.command.ifBlank { cmdPreview },
                                output = outcome.output,
                                exitCode = rec.exitCode,
                                durationMs = rec.durationMs,
                                verified = rec.verified,
                                actionId = actionIdFor(fnName, fnArgs)
                            )
                        )
                        maybeEmitArtifact(context, fnName, fnArgs, outcome, onEvent)

                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tcId)
                            put("content", outcome.output)
                        })
                    }
                    if (loopAborted) break
                } else if (parsedActions.isNotEmpty()) {
                    // Handle one or more JSON Action blocks
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", cleanContent)
                    })

                    val batchReport = StringBuilder()
                    var loopAborted = false

                    for ((actIdx, parsedAction) in parsedActions.withIndex()) {
                        val actionName = parsedAction.optString("action", parsedAction.optString("tool", "execute_shell_command"))
                        val cmd = parsedAction.optString("command", parsedAction.optString("cmd", ""))
                        val params = parsedAction.optJSONObject("params") ?: parsedAction
                        if (cmd.isNotBlank() && !params.has("command")) {
                            params.put("command", cmd)
                        }

                        val actionSig = "$actionName:${params.toString()}"
                        val repeats = executedToolSignatures.count { it == actionSig }
                        if (repeats >= 2) {
                            Log.w(TAG, "Infinite loop prevention: $actionName repeated $repeats times with identical args. Breaking.")
                            onStatus?.invoke("Breaking action loop; finalizing answer…")
                            finalResponseText = if (toolRecords.isNotEmpty()) {
                                "Task execution finished. Autonomous actions executed:\n" + toolRecords.takeLast(4).joinToString("\n") { "• ${it.command} (${it.exitCode})" }
                            } else {
                                "Completed requested operations."
                            }
                            if (onEvent == null) onChunk?.invoke(finalResponseText)
                            onEvent?.invoke(
                                com.pr4nav.jarvis.chat.AgentStreamEvent.TextDelta(finalResponseText)
                            )
                            onEvent?.invoke(
                                com.pr4nav.jarvis.chat.AgentStreamEvent.Final(
                                    text = finalResponseText,
                                    model = modelName,
                                    latencyMs = System.currentTimeMillis() - t0,
                                    handled = true,
                                    thinkingTrace = thinkingTrace,
                                    toolsUsed = toolRecords.size
                                )
                            )
                            loopAborted = true
                            break
                        }
                        executedToolSignatures.add(actionSig)

                        onStatus?.invoke("Executing $actionName (${actIdx + 1}/${parsedActions.size})…")
                        if (onEvent == null) onChunk?.invoke("$actionName: $cmd\n")
                        onEvent?.invoke(
                            com.pr4nav.jarvis.chat.AgentStreamEvent.ToolStart(
                                tool = actionName,
                                label = "",
                                detail = cmd
                            )
                        )

                        val outcome = executeTool(context, actionName, params)
                        val rec = outcome.copy(iteration = iteration)
                        toolRecords.add(rec)

                        val previewOut = if (outcome.output.length > 600) outcome.output.take(600) + "\n... [truncated]" else outcome.output
                        if (onEvent == null) onChunk?.invoke("$previewOut\n\n")
                        onEvent?.invoke(
                            com.pr4nav.jarvis.chat.AgentStreamEvent.ToolEnd(
                                tool = actionName,
                                label = "",
                                detail = rec.command.ifBlank { cmd },
                                output = outcome.output,
                                exitCode = rec.exitCode,
                                durationMs = rec.durationMs,
                                verified = rec.verified,
                                actionId = actionIdFor(actionName, params)
                            )
                        )
                        maybeEmitArtifact(context, actionName, params, outcome, onEvent)

                        batchReport.append("Action [${actIdx + 1}/${parsedActions.size}] ($actionName):\n${outcome.output}\n\n")
                    }
                    if (loopAborted) break

                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Autonomous execution results:\n${batchReport.toString().trim()}\nContinue task autonomously in YOLO mode until 100% complete.")
                    })
                }
            }

            if (finalResponseText.isBlank() || finalResponseText.equals("null", ignoreCase = true) || finalResponseText.equals("null null", ignoreCase = true)) {
                if (thinkingTrace.isNotBlank() && !thinkingTrace.equals("null", ignoreCase = true)) {
                    val (_, fallback) = com.pr4nav.jarvis.response.UserResponseSanitizer.stripThinking(thinkingTrace)
                    finalResponseText = if (fallback.isNotBlank() && !fallback.equals("null", ignoreCase = true) && !fallback.equals("null null", ignoreCase = true)) fallback else "Task completed successfully."
                } else {
                    finalResponseText = "Task completed successfully."
                }
            }

            val latency = System.currentTimeMillis() - t0
            return KiraResponse(
                success = true,
                response = finalResponseText,
                toolCallsExecuted = toolRecords,
                latencyMs = latency,
                thinkingTrace = thinkingTrace,
                modelUsed = modelName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during query with $modelName: ${e.message}", e)
            return KiraResponse(success = false, response = "", error = e.message, modelUsed = modelName)
        }
    }
}
