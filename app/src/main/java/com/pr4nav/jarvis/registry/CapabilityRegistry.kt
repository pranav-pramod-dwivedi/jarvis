package com.pr4nav.jarvis.registry

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

data class MatchResult(
    val capability: CapabilityDef,
    val params: Map<String, Any?>,
    val confidence: Double = 1.0
)

/**
 * Central Deterministic Capability Registry for JARVIS.
 * Aggregates 100+ native capabilities across all domains with
 * natural language alias matching and parameter extraction.
 */
object CapabilityRegistry {

    private const val TAG = "CapabilityRegistry"
    private val registry = LinkedHashMap<String, CapabilityDef>()
    private val aliasIndex = ArrayList<Pair<String, CapabilityDef>>()

    init {
        registerDomain(DeviceDomain.getCapabilities())
        registerDomain(MediaDomain.getCapabilities())
        registerDomain(AppDomain.getCapabilities())
        registerDomain(NavCommDomain.getCapabilities())
        registerDomain(FileDomain.getCapabilities())
        registerDomain(LinuxDomain.getCapabilities())
        registerDomain(AgentDomain.getCapabilities())
        registerDomain(SystemUiDomain.getCapabilities())
        registerDomain(WorkflowDomain.getCapabilities())
        try { Log.i(TAG, "Initialized CapabilityRegistry with ${registry.size} capabilities.") } catch (_: Throwable) {}
    }

    private fun registerDomain(caps: List<CapabilityDef>) {
        for (cap in caps) {
            registry[cap.id] = cap
            for (alias in cap.aliases) {
                aliasIndex.add(alias.lowercase().trim() to cap)
            }
        }
    }

    fun getAll(): List<CapabilityDef> = registry.values.toList()

    fun get(id: String): CapabilityDef? = registry[id]

    fun size(): Int = registry.size

    /**
     * Executes a registered capability by its unique ID,
     * validating permissions and risk constraints.
     */
    fun execute(id: String, params: Map<String, Any?>, context: Context): CapabilityExecutionResult {
        val t0 = System.currentTimeMillis()
        val cap = registry[id] ?: return CapabilityExecutionResult.fail("Capability '$id' not found in registry.")

        // Permission check
        if (cap.requiredPermission != null) {
            val granted = context.checkSelfPermission(cap.requiredPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return CapabilityExecutionResult.permissionRequired(
                    permission = cap.requiredPermission,
                    reason = "Capability '${cap.name}' requires permission."
                )
            }
        }

        return try {
            val res = cap.execute(context, params)
            val totalMs = System.currentTimeMillis() - t0
            res.copy(latencyMs = totalMs)
        } catch (e: Exception) {
            try { Log.e(TAG, "Error executing capability $id: ${e.message}", e) } catch (_: Throwable) {}
            CapabilityExecutionResult.fail("Error executing '${cap.name}': ${e.message}", latencyMs = System.currentTimeMillis() - t0)
        }
    }

    /**
     * Deterministic natural language matcher with regex-based parameter extraction (<1ms).
     */
    fun match(input: String): MatchResult? {
        val raw = input.trim()
        val lower = raw.lowercase()

        // 1. Direct Tool ID match
        if (registry.containsKey(raw)) {
            return MatchResult(registry[raw]!!, emptyMap(), 1.0)
        }

        // 2. Specialized Parameterized Patterns
        // Volume: "set volume to 50%", "volume 8", "set volume 5"
        val volPattern = Pattern.compile("(?:set\\s+)?volume(?:\\s+to)?\\s+(\\d+)(?:%)?")
        val volMatch = volPattern.matcher(lower)
        if (volMatch.find()) {
            val v = volMatch.group(1)?.toIntOrNull() ?: 7
            val cap = registry["system.volume.set"]
            if (cap != null) return MatchResult(cap, mapOf("value" to v), 0.98)
        }

        // Alarm: "set an alarm for 7:30 AM", "set alarm for 6 AM", "alarm at 7"
        val alarmPattern = Pattern.compile("(?:set\\s+(?:an\\s+)?)?alarm(?:\\s+for|\\s+at)?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        val alarmMatch = alarmPattern.matcher(lower)
        if (alarmMatch.find()) {
            var hr = alarmMatch.group(1)?.toIntOrNull() ?: 7
            val min = alarmMatch.group(2)?.toIntOrNull() ?: 0
            val ampm = alarmMatch.group(3)
            if (ampm == "pm" && hr < 12) hr += 12
            if (ampm == "am" && hr == 12) hr = 0
            val cap = registry["clock.alarm.set"]
            if (cap != null) return MatchResult(cap, mapOf("hour" to hr, "minute" to min), 0.98)
        }

        // Timer: "set a timer for 10 minutes", "timer 5 minutes", "timer for 30 seconds"
        val timerPattern = Pattern.compile("(?:set\\s+(?:a\\s+)?)?timer(?:\\s+for)?\\s+(\\d+)\\s*(minutes?|mins?|seconds?|secs?|hours?)?")
        val timerMatch = timerPattern.matcher(lower)
        if (timerMatch.find()) {
            val amt = timerMatch.group(1)?.toIntOrNull() ?: 5
            val unit = timerMatch.group(2) ?: "minutes"
            val secs = when {
                unit.startsWith("sec") -> amt
                unit.startsWith("hour") -> amt * 3600
                else -> amt * 60
            }
            val cap = registry["clock.timer.set"]
            if (cap != null) return MatchResult(cap, mapOf("seconds" to secs), 0.98)
        }

        // Spotify play: "play starboy on spotify", "play Bohemian Rhapsody on spotify"
        val spotifyPattern = Pattern.compile("play\\s+(.+?)\\s+on\\s+spotify", Pattern.CASE_INSENSITIVE)
        val spotifyMatch = spotifyPattern.matcher(raw)
        if (spotifyMatch.find()) {
            val q = spotifyMatch.group(1) ?: "music"
            val cap = registry["media.spotify.play"]
            if (cap != null) return MatchResult(cap, mapOf("query" to q), 0.98)
        }

        // Navigation: "navigate to airport", "take me to school", "directions to home"
        val navPattern = Pattern.compile("(?:navigate\\s+to|directions\\s+to|take\\s+me\\s+to)\\s+(.+)", Pattern.CASE_INSENSITIVE)
        val navMatch = navPattern.matcher(raw)
        if (navMatch.find()) {
            val dest = navMatch.group(1) ?: "home"
            val cap = registry["navigation.route"]
            if (cap != null) return MatchResult(cap, mapOf("destination" to dest), 0.98)
        }

        // Search files: "find file report.pdf", "find all .kt files", "search for budget"
        val filePattern = Pattern.compile("(?:find\\s+file|find\\s+all|search\\s+for\\s+file(?:s)?)\\s+(.+)", Pattern.CASE_INSENSITIVE)
        val fileMatch = filePattern.matcher(raw)
        if (fileMatch.find()) {
            val q = fileMatch.group(1) ?: ""
            val cap = registry["file.search"]
            if (cap != null) return MatchResult(cap, mapOf("query" to q), 0.98)
        }

        // Launch app: "open youtube", "open spotify", "launch maps"
        val appPattern = Pattern.compile("(?:open|launch|start)\\s+([a-zA-Z0-9\\s]+)")
        val appMatch = appPattern.matcher(lower)
        if (appMatch.find()) {
            val app = appMatch.group(1)?.trim() ?: ""
            // Specific shortcuts
            when (app) {
                "spotify" -> return MatchResult(registry["media.spotify.open"]!!, emptyMap(), 0.98)
                "youtube" -> return MatchResult(registry["media.youtube.open"]!!, emptyMap(), 0.98)
                "maps", "google maps" -> return MatchResult(registry["navigation.maps.open"]!!, emptyMap(), 0.98)
                "whatsapp" -> return MatchResult(registry["comm.whatsapp.open"]!!, emptyMap(), 0.98)
                "telegram" -> return MatchResult(registry["comm.telegram.open"]!!, emptyMap(), 0.98)
                "gmail", "email" -> return MatchResult(registry["comm.gmail.open"]!!, emptyMap(), 0.98)
                "messages", "sms" -> return MatchResult(registry["comm.messages.open"]!!, emptyMap(), 0.98)
                "camera" -> return MatchResult(registry["camera.open"]!!, emptyMap(), 0.98)
                "settings" -> return MatchResult(registry["settings.open"]!!, emptyMap(), 0.98)
                "terminal" -> return MatchResult(registry["gui.open.terminal"]!!, emptyMap(), 0.98)
                "opencode" -> return MatchResult(registry["agent.opencode.open"] ?: registry["opencode.open"]!!, emptyMap(), 0.98)
                "files", "file manager" -> return MatchResult(registry["gui.open.files"]!!, emptyMap(), 0.98)
                "dashboard" -> return MatchResult(registry["gui.open.dashboard"]!!, emptyMap(), 0.98)
                "diagnostics" -> return MatchResult(registry["gui.open.diagnostics"]!!, emptyMap(), 0.98)
            }
        }

        // 3. Exact Alias Matching
        for ((alias, cap) in aliasIndex) {
            if (lower == alias) {
                return MatchResult(cap, emptyMap(), 0.98)
            }
        }

        // 4. Prefix/Suffix Alias Matching (only if not compound)
        if (!lower.contains(" and ") && !lower.contains(" then ") && !lower.contains(";")) {
            for ((alias, cap) in aliasIndex) {
                if (lower.startsWith("$alias ") || lower.endsWith(" $alias")) {
                    return MatchResult(cap, emptyMap(), 0.90)
                }
            }
        }

        return null
    }

    /**
     * Export all registry schemas to Needle 2 tool format.
     */
    fun exportNeedleSchemas(): JSONArray {
        val arr = JSONArray()
        for (cap in registry.values) {
            val schema = JSONObject().apply {
                put("name", cap.id)
                put("description", cap.description)
                val params = JSONObject().apply {
                    put("type", "object")
                    val props = JSONObject()
                    for (req in cap.requiredParams) {
                        props.put(req, JSONObject().put("type", "string").put("description", req))
                    }
                    for (opt in cap.optionalParams) {
                        props.put(opt, JSONObject().put("type", "string").put("description", opt))
                    }
                    put("properties", props)
                    put("required", JSONArray(cap.requiredParams))
                }
                put("parameters", params)
            }
            arr.put(schema)
        }
        return arr
    }
}
