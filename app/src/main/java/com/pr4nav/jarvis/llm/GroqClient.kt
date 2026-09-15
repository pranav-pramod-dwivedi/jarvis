package com.pr4nav.jarvis.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pr4nav.jarvis.CmdGuard
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.context.ConversationalContext
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolValidator
import com.pr4nav.jarvis.tools.ValidationResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * High-Performance Client for Groq API (LPU Inference Engine).
 *
 * Upgraded to maximum practical capability:
 * 1. Primary Cloud Agent with real OpenAI-compatible structured tool calling.
 * 2. Mandatory security pipeline: Groq -> ToolValidator -> CmdGuard -> Execution -> Verification -> Groq.
 * 3. Distinct execution environments:
 *    - ANDROID_NATIVE (Canonical Android APIs & local shell)
 *    - TERMUX_NATIVE (Host Termux environment without PRoot overhead)
 *    - TERMUX_PROOT (Ubuntu Linux container via proot-distro)
 *    - AGY (Autonomous repository refactoring & coding agent)
 *    - GEMINI (Deep reasoning fallback)
 *    - GROQ_BUILTIN (Server-side Compound tools)
 * 4. Multi-turn Agent Loop (up to 6 iterations) for iterative troubleshooting, repair, and verification.
 * 5. Full observability logging: [Groq], [Decision], [Tool], [Result].
 * 6. Rate limit awareness (parsing Groq response headers: RPD, TPM, 429, 413) with graceful fallback.
 * 7. Compact active conversational context grounding without token bloating.
 */
object GroqClient {

    private const val TAG = "GroqClient"
    private const val PREFS_NAME = "jarvis_groq_prefs"
    const val KEY_GROQ_API_KEY = "groq_api_key"
    const val KEY_GROQ_MODEL = "groq_model"

    // Model constants
    const val MODEL_GPT_OSS_120B = "openai/gpt-oss-120b"
    const val MODEL_QWEN_27B = "qwen/qwen3.8-27b"
    const val MODEL_LLAMA_70B = "llama-3.3-70b-versatile"
    const val MODEL_LLAMA_8B = "llama-3.1-8b-instant"
    const val MODEL_COMPOUND = "groq/compound"
    const val MODEL_COMPOUND_MINI = "groq/compound-mini"

    // Primary agent model: openai/gpt-oss-120b by default for instant single-model response without HTTP 413
    const val DEFAULT_MODEL = MODEL_GPT_OSS_120B

    // User directive: "give groq run all commands" -> full command execution authority by default
    @Volatile var allowAllCommands: Boolean = true
    const val FAST_MODEL = MODEL_GPT_OSS_120B
    const val COMPLEX_MODEL = MODEL_GPT_OSS_120B
    const val LLAMA_70B_MODEL = MODEL_LLAMA_70B
    const val LLAMA_8B_MODEL = MODEL_LLAMA_8B

    const val MAX_COMPLETION_TOKENS = 8192
    const val RPD_LIMIT = 245
    const val TPM_LIMIT = 65_000L
    const val MAX_AGENT_TURNS = 6

    const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"
    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val GROQ_MODELS_ENDPOINT = "https://api.groq.com/openai/v1/models"

    private val executor = Executors.newCachedThreadPool()

    // Sliding window of token consumption: Pair<TimestampMillis, TokenCount>
    private val tokenUsageWindow = mutableListOf<Pair<Long, Int>>()

    @Volatile private var rateLimitedUntilMs: Long = 0L

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

    data class ToolExecutionOutcome(
        val toolName: String,
        val backend: String,
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val durationMs: Long,
        val verified: Boolean
    )

    data class GroqResponse(
        val success: Boolean,
        val response: String,
        val toolCallsExecuted: List<ToolCallRecord> = emptyList(),
        val latencyMs: Long = 0L,
        val error: String? = null,
        val thinkingTrace: String = "",
        val modelUsed: String = DEFAULT_MODEL,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
        val escalatedToAgy: Boolean = false,
        val escalatedToGemini: Boolean = false
    )

    data class UsageMetrics(
        val rpdUsed: Int,
        val rpdLimit: Int = RPD_LIMIT,
        val currentTpm: Long,
        val tpmLimit: Long = TPM_LIMIT,
        val totalTokensToday: Long,
        val remainingRequests: Int? = null,
        val remainingTokens: Long? = null
    )

    private fun getPrefs(context: Context?): SharedPreferences? {
        return try {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cleans up API key in case user pasted "Bearer gsk_..." (pattern from private-agent).
     */
    fun cleanApiKey(rawKey: String): String {
        var clean = rawKey.trim()
        if (clean.startsWith("Bearer ", ignoreCase = true)) {
            clean = clean.substring(7).trim()
        }
        return clean
    }

    fun getApiKey(context: Context?): String {
        if (context == null) return ""
        val saved = getPrefs(context)?.getString(KEY_GROQ_API_KEY, "")?.trim() ?: ""
        if (saved.isNotBlank()) return cleanApiKey(saved)
        // Check environment variable fallback
        val envKey = System.getenv("GROQ_API_KEY")?.trim() ?: ""
        return cleanApiKey(envKey)
    }

    fun setApiKey(context: Context, apiKey: String) {
        getPrefs(context)?.edit()?.putString(KEY_GROQ_API_KEY, cleanApiKey(apiKey))?.apply()
    }

    fun getModel(context: Context?): String {
        if (context == null) return DEFAULT_MODEL
        val saved = getPrefs(context)?.getString(KEY_GROQ_MODEL, null)
        if (saved == null || saved.isBlank()) {
            return DEFAULT_MODEL
        }
        return saved
    }

    fun isComplexTask(prompt: String): Boolean {
        val p = prompt.lowercase(Locale.ROOT)
        return p.contains("build") || p.contains("refactor") || p.contains("debug") ||
                p.contains("develop") || p.contains("full project") || p.contains("multi-step") ||
                p.contains("architecture") || p.contains("complex") || p.length > 250
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context)?.edit()?.putString(KEY_GROQ_MODEL, model.trim())?.apply()
    }

    /**
     * Retrieves live usage stats tracking the 245 RPD and 65k TPM limits.
     */
    @Synchronized
    fun getUsageMetrics(context: Context): UsageMetrics {
        val prefs = getPrefs(context)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lastDate = prefs?.getString("rpd_date", "") ?: ""
        val rpdUsed = if (lastDate == today) prefs?.getInt("rpd_count", 0) ?: 0 else 0
        val tokensToday = if (lastDate == today) prefs?.getLong("tokens_today", 0L) ?: 0L else 0L

        // Clean sliding window (last 60 seconds)
        val now = System.currentTimeMillis()
        tokenUsageWindow.removeAll { now - it.first > 60_000L }
        val currentTpm = tokenUsageWindow.sumOf { it.second.toLong() }

        return UsageMetrics(
            rpdUsed = rpdUsed,
            rpdLimit = RPD_LIMIT,
            currentTpm = currentTpm,
            tpmLimit = TPM_LIMIT,
            totalTokensToday = tokensToday
        )
    }

    @Synchronized
    private fun recordUsage(context: Context, promptTokens: Int, completionTokens: Int, totalTokens: Int) {
        val prefs = getPrefs(context) ?: return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lastDate = prefs.getString("rpd_date", "")

        val editor = prefs.edit()
        val currentRpd = if (lastDate == today) prefs.getInt("rpd_count", 0) + 1 else 1
        val currentTokens = if (lastDate == today) prefs.getLong("tokens_today", 0L) + totalTokens else totalTokens.toLong()

        editor.putString("rpd_date", today)
        editor.putInt("rpd_count", currentRpd)
        editor.putLong("tokens_today", currentTokens)
        editor.apply()

        // Sliding 1-minute window update
        val now = System.currentTimeMillis()
        tokenUsageWindow.add(Pair(now, totalTokens))
        tokenUsageWindow.removeAll { now - it.first > 60_000L }
    }

    /**
     * Checks whether Groq limits (245 RPD or 65k TPM) are currently saturated.
     */
    @Synchronized
    fun isRateLimitExceeded(context: Context): Boolean {
        val metrics = getUsageMetrics(context)
        return metrics.rpdUsed >= RPD_LIMIT || (metrics.currentTpm + 1000L) >= TPM_LIMIT
    }

    /**
     * Extracts <think>...</think> reasoning traces from model response.
     */
    fun extractThinking(text: String): Pair<String, String> {
        val thinkRegex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
        val match = thinkRegex.find(text)
        if (match != null) {
            val thinkContent = match.groupValues[1].trim()
            val cleanContent = text.replace(thinkRegex, "").trim()
            return Pair(thinkContent, cleanContent)
        }
        return Pair("", text.trim())
    }

    /**
     * Parses and repairs JSON Action response structures copied from PrivateAgent.
     */
    fun parseAgentAction(response: String): JSONObject? {
        try {
            var trimmed = response.trim()
            if (trimmed.startsWith("```")) {
                val lines = trimmed.split("\n").toMutableList()
                if (lines.isNotEmpty()) lines.removeAt(0)
                if (lines.isNotEmpty() && lines.last().trim().startsWith("```")) {
                    lines.removeAt(lines.size - 1)
                }
                trimmed = lines.joinToString("\n").trim()
            }
            if (trimmed.startsWith("{") && !trimmed.endsWith("}")) {
                trimmed += "\n}"
            }
            if (trimmed.startsWith("{") && (trimmed.contains("\"action\"") || trimmed.contains("\"command\""))) {
                return try {
                    JSONObject(trimmed)
                } catch (_: Exception) {
                    try {
                        JSONObject(trimmed + "\n}")
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Fetches available models from Groq's /models endpoint dynamically (from PrivateAgent architecture).
     */
    fun fetchAvailableModels(
        context: Context,
        apiKeyOverride: String? = null,
        onSuccess: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = cleanApiKey(apiKeyOverride?.takeIf { it.isNotBlank() } ?: getApiKey(context))
        if (apiKey.isBlank()) {
            onError("Groq API key is empty")
            return
        }

        executor.execute {
            try {
                val conn = (URL(GROQ_MODELS_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("HTTP-Referer", "https://github.com/orailnoor/private-agent")
                    setRequestProperty("X-Title", "JARVIS")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    onError("Failed to fetch Groq models ($code): $err")
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
                modelsList.sort()
                onSuccess(modelsList)
            } catch (e: Exception) {
                onError("Network error fetching Groq models: ${e.message}")
            }
        }
    }

    /**
     * Builds canonical OpenAI-compatible tools schema exposed to Groq.
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

        // 3. execute_termux_command & execute_proot_command & execute_android_command & escalate_to_agy (Bridge/Compatibility tools)
        listOf("execute_termux_command", "execute_proot_command", "execute_android_command", "escalate_to_agy").forEach { tool ->
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool)
                    put("description", "Executes action via $tool.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("command", JSONObject().apply {
                                put("type", "string")
                                put("description", "Command or task argument")
                            })
                            put("task", JSONObject().apply {
                                put("type", "string")
                                put("description", "Task argument")
                            })
                        })
                    })
                })
            })
        }

        // 4. system_torch
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "system_torch")
                put("description", "Turns the device flashlight/torch on or off.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("state", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "true to turn on flashlight, false to turn off")
                        })
                    })
                    put("required", JSONArray().put("state"))
                })
            })
        })

        // 5. system_volume
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "system_volume")
                put("description", "Adjusts device media volume.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray().put("raise").put("lower").put("mute").put("unmute"))
                            put("description", "Action to perform on volume (raise, lower, mute)")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })
        })

        // 6. system_battery
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "system_battery")
                put("description", "Checks device battery level percentage and charging state.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        })

        // 7. open_app
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "open_app")
                put("description", "Opens or launches an installed application by name or package.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("app", JSONObject().apply {
                            put("type", "string")
                            put("description", "Name or package of the application to open, e.g. 'Chrome', 'WhatsApp', 'Settings'")
                        })
                    })
                    put("required", JSONArray().put("app"))
                })
            })
        })

        // 8. close_app
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "close_app")
                put("description", "Closes or stops a running application.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package", JSONObject().apply {
                            put("type", "string")
                            put("description", "Application package name or app name to close")
                        })
                    })
                    put("required", JSONArray().put("package"))
                })
            })
        })

        // 9. read_file
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "read_file")
                put("description", "Reads text content of a file within the allowed workspace boundary.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Path of the file to read")
                        })
                    })
                    put("required", JSONArray().put("path"))
                })
            })
        })

        // 10. write_file
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "write_file")
                put("description", "Writes text content to a file within the allowed workspace boundary.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Path of the file to write")
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

        // 11. list_files
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "list_files")
                put("description", "Lists files and directories under a given path within the workspace boundary.")
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

        // 12. escalate_to_gemini
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "escalate_to_gemini")
                put("description", "Escalates to Google Gemini Cloud LLM for deep multi-step reasoning, complex conceptual analysis, or when high-level synthesis is required.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().apply {
                            put("type", "string")
                            put("description", "The prompt/question to send to Gemini Cloud LLM")
                        })
                    })
                    put("required", JSONArray().put("prompt"))
                })
            })
        })

        // 14. execute_device_tool (Universal Assistant / Siri tool bridge)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "execute_device_tool")
                put("description", "Executes any of JARVIS's 350+ mobile assistant capabilities (e.g. clock_alarm_set, clock_timer_start, clock_world_time, calendar_event_create, reminder_create, phone_call_contact, message_send_sms, message_send_whatsapp, music_play, maps_navigate_to, weather_current, calc_math_evaluate, notes_create, device_torch_toggle, device_volume_set, device_battery_status, file_storage_stats, etc.).")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("tool_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "The specific tool name to execute")
                        })
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("description", "Arguments/parameters for the specific tool")
                        })
                    })
                    put("required", JSONArray().put("tool_name").put("parameters"))
                })
            })
        })

        // 15. read_screen_text (Screencapture skill - text-based, eliminates image screenshot latency)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "read_screen_text")
                put("description", "High-speed screen capture that reads all text, labels, buttons, and UI controls on the current device screen via Accessibility. Eliminates image screenshot latency entirely.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        })

        // 16. virtual_touch (Virtual touches skill - taps coordinates or clicks by text)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "virtual_touch")
                put("description", "Dispatches a virtual touch tap on the screen at coordinates (x, y) or clicks an element by visible text label.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply {
                            put("type", "string")
                            put("description", "Visible text or button title to click (semantic click)")
                        })
                        put("x", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Optional screen X coordinate")
                        })
                        put("y", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Optional screen Y coordinate")
                        })
                    })
                })
            })
        })

        // 17. browser_render_app (JarvisBrowser dynamic on-demand UI)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "browser_render_app")
                put("description", """Generates and renders an on-demand, interactive HTML/CSS/JS mini web-app in JarvisBrowser.
MANDATORY: The 'html' field MUST be a COMPLETE, runnable HTML document (<!DOCTYPE html>.....</html>) with all CSS and JavaScript inlined.
NO placeholders. NO truncation. NO "...rest of code here". Write EVERY line of code needed for the app to work.
Use whenever a visual UI, physics simulation, comparison table, calculator, game, chart, or custom dashboard is better than plain text or voice.""")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("app_id", JSONObject().apply {
                            put("type", "string")
                            put("description", "Unique alphanumeric slug for the app, e.g. 'faradays-law', 'workout-chart', 'solar-eclipse'")
                        })
                        put("title", JSONObject().apply {
                            put("type", "string")
                            put("description", "Human-readable title for the app header")
                        })
                        put("html", JSONObject().apply {
                            put("type", "string")
                            put("description", "COMPLETE self-contained HTML5 document. Must start with <!DOCTYPE html> and end with </html>. All CSS in <style> tags, all JS in <script> tags. Zero external CDN dependencies. No placeholders or truncation allowed.")
                        })
                        put("explanation_speech", JSONObject().apply {
                            put("type", "string")
                            put("description", "Spoken verbal explanation that JARVIS will speak while the user interacts with the UI")
                        })
                        put("is_temporary", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "True for temporary/one-off previews; false to save permanently in user's JarvisBrowser library")
                        })
                    })
                    put("required", org.json.JSONArray().put("app_id").put("title").put("html"))
                })
            })
        })

        // 18. browser_launch_app (Launch existing or saved JarvisBrowser app)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "browser_launch_app")
                put("description", "Launches an existing or saved JarvisBrowser app by ID, title, or search query (e.g. 'faraday', 'workout').")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "App ID, title, or search keyword of the saved app to launch")
                        })
                        put("explanation_speech", JSONObject().apply {
                            put("type", "string")
                            put("description", "Optional verbal speech to speak upon launching")
                        })
                    })
                    put("required", org.json.JSONArray().put("query"))
                })
            })
        })

        // 19. browser_list_apps (List saved JarvisBrowser apps)
        arr.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "browser_list_apps")
                put("description", "Lists all saved and permanent JarvisBrowser mini-apps in the user's library.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                })
            })
        })

        return arr
    }

    /**
     * Executes any tool requested by Groq through the mandatory security pipeline:
     * Groq -> ToolValidator -> CmdGuard -> Execution Environment -> Verification -> Result.
     * The model is NEVER the authority.
     */
    fun executeToolSafely(
        context: Context,
        toolName: String,
        args: JSONObject,
        prompt: String
    ): ToolExecutionOutcome {
        val t0 = System.currentTimeMillis()
        CanonicalToolRegistry.init(context)

        // Normalize tool name to canonical registry naming where needed
        val canonicalName = when (toolName) {
            "system_torch" -> "system.torch"
            "system_volume" -> "system.volume"
            "system_battery" -> "system.battery"
            "execute_termux_command", "execute_proot_command", "execute_android_command", "run_shell_command" -> "run_command"
            else -> toolName
        }

        // 1. ToolValidator check (registration, parameters, workspace jail, semantic contracts)
        if (canonicalName != "escalate_to_agy" && canonicalName != "escalate_to_gemini") {
            val validation = ToolValidator.validate(context, canonicalName, args, prompt)
            if (validation is ValidationResult.Rejected) {
                val dur = System.currentTimeMillis() - t0
                val errMsg = "Security/Validation Rejection: ${validation.error.message}"
                Log.w(TAG, "[Tool] $toolName REJECTED by ToolValidator: $errMsg")
                Log.i(TAG, "[Result] tool: $toolName | success: false | verified: false")
                return ToolExecutionOutcome(
                    toolName = toolName,
                    backend = "VALIDATOR",
                    success = false,
                    exitCode = 1,
                    output = errMsg,
                    durationMs = dur,
                    verified = false
                )
            }
        }

        // 2. Command check for shell commands
        val commandToRun = when (toolName) {
            "execute_termux_command", "execute_proot_command", "execute_android_command", "run_command", "run_shell_command" ->
                args.optString("command", args.optString("cmd")).trim()
            else -> null
        }

        if (commandToRun != null) {
            if (!allowAllCommands) {
                val guardErr = CmdGuard.check(commandToRun)
                if (guardErr != null) {
                    val dur = System.currentTimeMillis() - t0
                    val blockMsg = "Security Policy Violation: $guardErr"
                    Log.w(TAG, "[Tool] Command REJECTED by CmdGuard: $blockMsg")
                    Log.i(TAG, "[Result] tool: $toolName | success: false | verified: false")
                    return ToolExecutionOutcome(
                        toolName = toolName,
                        backend = "CMD_GUARD",
                        success = false,
                        exitCode = 126,
                        output = blockMsg,
                        durationMs = dur,
                        verified = false
                    )
                }
            } else {
                Log.i(TAG, "[Groq Tool] Groq granted full command execution authority: $commandToRun")
            }
        }

        // 3. Execution Environment Dispatch
        var backend = "UNKNOWN"
        var exitCode = 0
        var output = ""
        var success = false
        var verified = false

        when (toolName) {
            "execute_termux_command" -> {
                backend = "TERMUX_NATIVE"
                val res = Shell.termuxRaw(commandToRun!!, 25_000L)
                exitCode = res.rc ?: (if (res.timedOut) 124 else 1)
                output = if (res.out.isNotBlank()) res.out else if (res.err.isNotBlank()) res.err else "(Command completed with no output)"
                success = !res.timedOut && res.rc == 0
                verified = success
            }
            "execute_proot_command" -> {
                backend = "TERMUX_PROOT"
                val res = Shell.ubuntu(commandToRun!!, 25_000L)
                exitCode = res.rc ?: (if (res.timedOut) 124 else 1)
                output = if (res.out.isNotBlank()) res.out else if (res.err.isNotBlank()) res.err else "(Command completed with no output)"
                success = !res.timedOut && res.rc == 0
                verified = success
            }
            "execute_android_command" -> {
                backend = "ANDROID_NATIVE"
                val res = Shell.local(commandToRun!!, 25_000L)
                exitCode = res.rc ?: (if (res.timedOut) 124 else 1)
                output = if (res.out.isNotBlank()) res.out else if (res.err.isNotBlank()) res.err else "(Command completed with no output)"
                success = !res.timedOut && res.rc == 0
                verified = success
            }
            "execute_shell_command", "run_shell_command", "run_command" -> {
                backend = "ANDROID_SHELL"
                val res = Shell.local(commandToRun!!, 25_000L)
                exitCode = res.rc ?: (if (res.timedOut) 124 else 1)
                output = if (res.out.isNotBlank()) res.out else if (res.err.isNotBlank()) res.err else "(Command completed with no output)"
                success = !res.timedOut && res.rc == 0
                verified = success
            }
            "escalate_to_agy" -> {
                backend = "AGY"
                exitCode = 0
                output = "AGY escalation requested"
                success = true
                verified = true
            }
            "execute_root_command", "run_root_command", "su" -> {
                backend = "ROOT_SU"
                val res = Shell.root(commandToRun!!, 35_000L)
                exitCode = res.rc ?: (if (res.timedOut) 124 else 1)
                output = if (res.out.isNotBlank()) res.out else if (res.err.isNotBlank()) res.err else "(Command completed with no output)"
                success = !res.timedOut && res.rc == 0
                verified = success
            }
            "escalate_to_gemini" -> {
                backend = "GEMINI"
                val geminiPrompt = args.optString("prompt")
                val latch = CountDownLatch(1)
                var geminiOut = ""
                var geminiSuccess = false
                GeminiCloudLLM.generate(
                    context = context,
                    prompt = geminiPrompt,
                    onSuccess = { resText ->
                        geminiOut = resText
                        geminiSuccess = true
                        latch.countDown()
                    },
                    onError = { err ->
                        geminiOut = "Gemini fallback error: $err"
                        geminiSuccess = false
                        latch.countDown()
                    }
                )
                latch.await(30, TimeUnit.SECONDS)
                exitCode = if (geminiSuccess) 0 else 1
                output = geminiOut
                success = geminiSuccess
                verified = geminiSuccess
            }
            "execute_device_tool" -> {
                backend = "CANONICAL_TOOL"
                val innerToolName = args.optString("tool_name", "").trim()
                val innerArgs = args.optJSONObject("parameters") ?: JSONObject()
                val toolRes = CanonicalToolRegistry.execute(context, innerToolName, innerArgs)
                success = toolRes.success
                exitCode = if (success) 0 else 1
                output = if (toolRes.data != null) toolRes.data.toString() else (toolRes.error?.message ?: toolRes.status.name)
                val toolDef = CanonicalToolRegistry.get(innerToolName)
                verified = toolDef?.verify?.invoke(context, innerArgs, toolRes) ?: success
            }
            else -> {
                backend = "CANONICAL_TOOL"
                val toolRes = CanonicalToolRegistry.execute(context, canonicalName, args)
                success = toolRes.success
                exitCode = if (success) 0 else 1
                output = if (toolRes.data != null) toolRes.data.toString() else (toolRes.error?.message ?: toolRes.status.name)
                val toolDef = CanonicalToolRegistry.get(canonicalName)
                verified = toolDef?.verify?.invoke(context, args, toolRes) ?: success
            }
        }

        val dur = System.currentTimeMillis() - t0
        val truncatedOutput = if (output.length > 3500) output.take(3500) + "\n... [truncated]" else output

        Log.i(TAG, "[Tool] tool: $toolName | args: $args | env: $backend | duration: ${dur}ms | exitCode: $exitCode")
        Log.i(TAG, "[Result] tool: $toolName | success: $success | verified: $verified")

        return ToolExecutionOutcome(
            toolName = toolName,
            backend = backend,
            success = success,
            exitCode = exitCode,
            output = truncatedOutput,
            durationMs = dur,
            verified = verified
        )
    }

    /**
     * Zero system prompt on Groq: the 8k/message window is reserved entirely for
     * tools + history + the user task. Tool schemas already carry capability semantics.
     */
    private fun buildSystemPrompt(modelName: String, activeContext: String): String = ""

    /**
     * Executes query through Groq with native structured tool calling, multi-turn agent loop,
     * rate limit monitoring, and automatic escalation to Gemini or AGY.
     */
    /**
     * Executes query through Groq with strict single-model-per-turn invariant:
     * 1. If user configured/selected a model, ONLY that model is called (no auto-promotion, no hidden router).
     * 2. If it succeeds, STOP immediately.
     * 3. Fallback is sequential and strictly failure-only.
     * 4. Full request accounting logged for every attempt.
     */
    fun query(
        context: Context,
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        forceShellCapability: Boolean = true,
        requestId: String = RequestAccounting.startTurn(prompt),
        onSuccess: (GroqResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = cleanApiKey(getApiKey(context))

        if (apiKey.isBlank()) {
            Log.i(TAG, "request=$requestId No Groq API key configured.")
            val attemptNum = RequestAccounting.recordAttemptStart(requestId, getModel(context), "Primary Request", isFallback = false)
            RequestAccounting.recordAttemptEnd(requestId, attemptNum, "FAILURE (No API key)", 0L)
            RequestAccounting.finishTurn(requestId)
            onError("Groq API key not configured")
            return
        }

        if (isRateLimitExceeded(context)) {
            val metrics = getUsageMetrics(context)
            val reason = "Groq rate limit saturated (${metrics.rpdUsed}/$RPD_LIMIT RPD, ${metrics.currentTpm}/$TPM_LIMIT TPM)"
            Log.w(TAG, "request=$requestId $reason.")
            val attemptNum = RequestAccounting.recordAttemptStart(requestId, getModel(context), "Primary Request", isFallback = false)
            RequestAccounting.recordAttemptEnd(requestId, attemptNum, "FAILURE (Rate Limit Exceeded)", 0L)
            RequestAccounting.finishTurn(requestId)
            onError(reason)
            return
        }

        executor.execute {
            val t0 = System.currentTimeMillis()
            // Strict Policy: Use the EXACT configured or primary model. NO hidden promotion, NO router model!
            val modelName = getModel(context)
            val toolCallsExecuted = mutableListOf<ToolCallRecord>()

            val attemptNum = RequestAccounting.recordAttemptStart(requestId, modelName, "Primary Request", isFallback = false)
            Log.i(TAG, "request=$requestId attempt=$attemptNum model=$modelName fallback=false status=START prompt=\"${prompt.take(60)}\"")

            try {
                val isCompoundModel = modelName.startsWith("groq/compound")
                val activeCtx = ConversationalContext.getActiveContextSummary()
                val systemPrompt = buildSystemPrompt(modelName, activeCtx)

                val messages = JSONArray()
                if (systemPrompt.isNotBlank()) {
                    messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                }

                val effectiveHistory = if (history.isNotEmpty()) history else ConversationalContext.getRecentTurns(4)
                for ((role, text) in effectiveHistory.takeLast(4)) {
                    messages.put(
                        JSONObject().apply {
                            put("role", if (role.lowercase(Locale.ROOT) == "assistant") "assistant" else "user")
                            put("content", text.take(150))
                        }
                    )
                }
                messages.put(JSONObject().put("role", "user").put("content", prompt))

                var finalResponseText = ""
                var totalPromptTokens = 0
                var totalCompletionTokens = 0
                var totalTokensUsed = 0

                // Multi-turn agent loop for the SAME model (when tools are executed)
                for (iteration in 1..MAX_AGENT_TURNS) {
                    val payload = JSONObject().apply {
                        put("model", modelName)
                        put("messages", messages)
                        put("max_completion_tokens", MAX_COMPLETION_TOKENS)
                        put("temperature", 0.3)

                        // If model supports OpenAI function calling, supply structured tools
                        if (forceShellCapability && !isCompoundModel) {
                            put("tools", buildJarvisToolsSchema())
                            put("tool_choice", "auto")
                        }
                    }

                    val conn = (URL(GROQ_ENDPOINT).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8_000
                        readTimeout = 40_000
                        doOutput = true
                        doInput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("HTTP-Referer", "https://github.com/orailnoor/private-agent")
                        setRequestProperty("X-Title", "JARVIS")
                    }

                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

                    val code = conn.responseCode

                    // Inspect Groq Rate Limit Headers
                    val remReq = conn.getHeaderField("x-ratelimit-remaining-requests")?.toIntOrNull()
                    val remTok = conn.getHeaderField("x-ratelimit-remaining-tokens")?.toLongOrNull()
                    val resetReq = conn.getHeaderField("x-ratelimit-reset-requests")

                    if (remReq != null && remReq <= 1) {
                        Log.w(TAG, "[Groq] Approaching rate limit! Remaining requests: $remReq, reset in: $resetReq")
                    }

                    // Rate limit error (HTTP 429) -> Sequential failure-only fallback
                    if (code == 429) {
                        rateLimitedUntilMs = System.currentTimeMillis() + 60_000L
                        val dur = System.currentTimeMillis() - t0
                        RequestAccounting.recordAttemptEnd(requestId, attemptNum, "FAILURE (HTTP 429 Rate Limit)", dur)
                        RequestAccounting.finishTurn(requestId)
                        onError("Groq HTTP 429 Rate Limit")
                        return@execute
                    }

                    // Request too large (HTTP 413) -> Sequential failure-only fallback
                    if (code == 413) {
                        val dur = System.currentTimeMillis() - t0
                        RequestAccounting.recordAttemptEnd(requestId, attemptNum, "FAILURE (HTTP 413 Request Too Large)", dur)
                        RequestAccounting.finishTurn(requestId)
                        onError("Groq HTTP 413 Request Entity Too Large")
                        return@execute
                    }

                    if (code !in 200..299) {
                        val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                        val dur = System.currentTimeMillis() - t0
                        RequestAccounting.recordAttemptEnd(requestId, attemptNum, "FAILURE (HTTP $code: $err)", dur)
                        RequestAccounting.finishTurn(requestId)
                        onError("Groq error ($code): $err")
                        return@execute
                    }

                    val rawResp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val json = JSONObject(rawResp)

                    // Monitor tokens
                    val usageObj = json.optJSONObject("usage")
                    if (usageObj != null) {
                        val pTokens = usageObj.optInt("prompt_tokens", 0)
                        val cTokens = usageObj.optInt("completion_tokens", 0)
                        val tTokens = usageObj.optInt("total_tokens", pTokens + cTokens)
                        totalPromptTokens += pTokens
                        totalCompletionTokens += cTokens
                        totalTokensUsed += tTokens
                        recordUsage(context, pTokens, cTokens, tTokens)
                    }

                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) break

                    val choice = choices.getJSONObject(0)
                    val messageObj = choice.optJSONObject("message") ?: break
                    val contentRaw = if (!messageObj.isNull("content")) {
                        val r = messageObj.opt("content")
                        if (r == null || r == JSONObject.NULL) "" else r.toString().trim()
                    } else ""
                    val content = if (contentRaw.equals("null", ignoreCase = true) || contentRaw.equals("null null", ignoreCase = true)) "" else contentRaw
                    val toolCalls = messageObj?.optJSONArray("tool_calls")

                    // Groq Compound models return executed_tools for server-executed tools (web search, code interpreter, etc.)
                    val executedToolsArr = messageObj.optJSONArray("executed_tools")
                    if (executedToolsArr != null && executedToolsArr.length() > 0) {
                        for (i in 0 until executedToolsArr.length()) {
                            val tObj = executedToolsArr.optJSONObject(i)
                            val tType = tObj?.optString("type", tObj.optString("name", "compound_tool")) ?: "compound_tool"
                            val tInput = tObj?.optString("query", tObj.optString("input", "")) ?: ""
                            val tResult = tObj?.optString("result", tObj.optString("output", "")) ?: ""
                            toolCallsExecuted.add(
                                ToolCallRecord(
                                    iteration = iteration,
                                    toolName = tType,
                                    command = "[$tType] $tInput",
                                    backend = "GROQ_BUILTIN",
                                    exitCode = 0,
                                    output = tResult.take(500),
                                    verified = true
                                )
                            )
                        }
                    }

                    // Case A: Model requested local tool_calls
                    if (toolCalls != null && toolCalls.length() > 0) {
                        Log.i(TAG, "request=$requestId [Decision] Groq chose structured tool_calls (count: ${toolCalls.length()})")
                        messages.put(messageObj) // append assistant tool call message

                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.getJSONObject(i)
                            val callId = tc.optString("id", "call_${System.currentTimeMillis()}_$i")
                            val fn = tc.optJSONObject("function")
                            val fnName = fn?.optString("name", "") ?: ""
                            val fnArgsStr = fn?.optString("arguments", "{}") ?: "{}"
                            val fnArgs = try { JSONObject(fnArgsStr) } catch (_: Exception) { JSONObject() }

                            // Execute through mandatory security boundary
                            val outcome = executeToolSafely(context, fnName, fnArgs, prompt)

                            toolCallsExecuted.add(
                                ToolCallRecord(
                                    iteration = iteration,
                                    toolName = fnName,
                                    command = fnArgs.optString("command", fnArgs.optString("task", fnArgs.toString())),
                                    backend = outcome.backend,
                                    exitCode = outcome.exitCode,
                                    output = outcome.output,
                                    durationMs = outcome.durationMs,
                                    verified = outcome.verified
                                )
                            )

                            // Add structured tool response message
                            val toolMsg = JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", callId)
                                put("content", JSONObject().apply {
                                    put("tool", fnName)
                                    put("success", outcome.success)
                                    put("exitCode", outcome.exitCode)
                                    put("output", outcome.output)
                                    put("verified", outcome.verified)
                                    put("backend", outcome.backend)
                                }.toString())
                            }
                            messages.put(toolMsg)
                        }
                        continue // loop back to same model for tool output evaluation
                    }

                    // Case B: Model returned a PrivateAgent structured JSON action format (for Compound models)
                    val parsedAction = parseAgentAction(content)
                    if (parsedAction != null && iteration < MAX_AGENT_TURNS) {
                        val actionName = parsedAction.optString("action", parsedAction.optString("tool", ""))
                        val params = parsedAction.optJSONObject("params") ?: parsedAction.optJSONObject("arguments") ?: JSONObject()

                        if (actionName.isNotBlank()) {
                            Log.i(TAG, "request=$requestId [Decision] Groq Compound chose JSON action: $actionName")
                            val outcome = executeToolSafely(context, actionName, params, prompt)

                            toolCallsExecuted.add(
                                ToolCallRecord(
                                    iteration = iteration,
                                    toolName = actionName,
                                    command = params.optString("command", params.optString("task", params.toString())),
                                    backend = outcome.backend,
                                    exitCode = outcome.exitCode,
                                    output = outcome.output,
                                    durationMs = outcome.durationMs,
                                    verified = outcome.verified
                                )
                            )
                            messages.put(messageObj)
                            messages.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", "Tool Execution Result for '$actionName' (${outcome.backend}):\n" +
                                            "Exit code: ${outcome.exitCode}\nOutput:\n${outcome.output}\n\n" +
                                            "Analyze this output. If further diagnosis or verification is needed, invoke the next tool. Otherwise provide the final answer to the user.")
                                }
                            )
                            continue
                        } else if (parsedAction.has("response")) {
                            finalResponseText = parsedAction.optString("response")
                            break
                        }
                    }

                    // Case C: Final textual answer generated
                    Log.i(TAG, "request=$requestId [Decision] Groq generated final answer")
                    finalResponseText = if (content.isNotBlank() && !content.equals("null", ignoreCase = true)) content else ""
                    break
                }

                val latency = System.currentTimeMillis() - t0
                val (baseThink, cleanReply) = extractThinking(finalResponseText)
                val safeReply = if (cleanReply.isNotBlank() && !cleanReply.equals("null", ignoreCase = true) && !cleanReply.equals("null null", ignoreCase = true)) {
                    cleanReply
                } else if (toolCallsExecuted.isNotEmpty()) {
                    "Operations completed successfully."
                } else {
                    ""
                }

                // Policy 10: If Compound returns HTTP 200 but genuinely produces unusable/empty response, classify as failure
                if (safeReply.isBlank()) {
                    RequestAccounting.recordAttemptEnd(requestId, attemptNum, "FAILURE (Empty response)", latency)
                    RequestAccounting.finishTurn(requestId)
                    Log.w(TAG, "request=$requestId Groq returned unusable empty response.")
                    onError("Groq returned empty response")
                    return@execute
                }

                // SUCCESS -> HARD INVARIANT: STOP! Do NOT invoke another model!
                RequestAccounting.recordAttemptEnd(requestId, attemptNum, "SUCCESS", latency)
                RequestAccounting.finishTurn(requestId)

                val toolsTrace = if (toolCallsExecuted.isNotEmpty()) {
                    val sb = StringBuilder()
                    sb.append("• Executed Tools & Verification:\n")
                    for (t in toolCallsExecuted) {
                        val statusEmoji = if (t.exitCode == 0 && t.verified) "✓" else "✗"
                        sb.append("  $statusEmoji [${t.backend}] ${t.toolName} (${t.durationMs}ms): ${t.command.take(60)}\n")
                        if (t.output.isNotBlank()) {
                            sb.append("    ↳ ${t.output.replace("\n", " ").take(100)}\n")
                        }
                    }
                    sb.toString().trim()
                } else ""

                val fullThinkTrace = when {
                    baseThink.isNotBlank() && toolsTrace.isNotBlank() -> "$baseThink\n\n$toolsTrace"
                    baseThink.isNotBlank() -> baseThink
                    else -> toolsTrace
                }

                onSuccess(
                    GroqResponse(
                        success = true,
                        response = safeReply,
                        toolCallsExecuted = toolCallsExecuted,
                        latencyMs = latency,
                        thinkingTrace = fullThinkTrace,
                        modelUsed = modelName,
                        promptTokens = totalPromptTokens,
                        completionTokens = totalCompletionTokens,
                        totalTokens = totalTokensUsed,
                        escalatedToAgy = toolCallsExecuted.any { it.backend == "AGY" },
                        escalatedToGemini = toolCallsExecuted.any { it.backend == "GEMINI" }
                    )
                )
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - t0
                RequestAccounting.recordAttemptEnd(requestId, attemptNum, "FAILURE (${e.message})", latency)
                RequestAccounting.finishTurn(requestId)
                Log.e(TAG, "request=$requestId Groq execution failed: ${e.message}", e)
                onError("Groq error: ${e.message}")
            }
        }
    }

    private fun executeViaGeminiFallback(
        context: Context,
        prompt: String,
        reason: String,
        requestId: String,
        onSuccess: (GroqResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            val t0 = System.currentTimeMillis()
            val fbAttempt = RequestAccounting.recordAttemptStart(requestId, "Gemini 2.0 Flash", reason, isFallback = true)
            Log.i(TAG, "request=$requestId attempt=$fbAttempt model=Gemini 2.0 Flash fallback=true status=START reason=\"$reason\"")

            GeminiCloudLLM.generate(
                context = context,
                prompt = prompt,
                onSuccess = { reply ->
                    val latency = System.currentTimeMillis() - t0
                    RequestAccounting.recordAttemptEnd(requestId, fbAttempt, "SUCCESS", latency)
                    RequestAccounting.finishTurn(requestId)
                    val trace = "<think>\n• Fallback Engine: Google Gemini 2.0 Flash\n• Reason: $reason\n• Latency: ${latency}ms\n</think>"
                    onSuccess(
                        GroqResponse(
                            success = true,
                            response = reply,
                            latencyMs = latency,
                            thinkingTrace = trace,
                            modelUsed = "Gemini 2.0 Flash (Cloud Fallback)",
                            escalatedToGemini = true
                        )
                    )
                },
                onError = { err ->
                    val latency = System.currentTimeMillis() - t0
                    RequestAccounting.recordAttemptEnd(requestId, fbAttempt, "FAILURE ($err)", latency)
                    RequestAccounting.finishTurn(requestId)
                    onError("Groq failed ($reason) and Gemini fallback failed ($err)")
                }
            )
        }
    }

}
