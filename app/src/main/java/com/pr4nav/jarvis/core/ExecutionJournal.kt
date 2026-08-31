package com.pr4nav.jarvis.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID

enum class ExecutionPhase {
    PREPARE,
    EXECUTE,
    VERIFY,
    COMPLETED,
    FAILED
}

data class JournalEntry(
    val executionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val rawRequest: String,
    val normalizedIntent: String,
    val tool: String,
    val args: JSONObject,
    val backend: String,
    val phase: ExecutionPhase,
    val success: Boolean,
    val verified: Boolean,
    val durationMs: Long,
    val error: String? = null
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("execution_id", executionId)
        put("timestamp", timestamp)
        put("request", sanitize(rawRequest))
        put("intent", normalizedIntent)
        put("tool", tool)
        put("args", args)
        put("backend", backend)
        put("phase", phase.name)
        put("success", success)
        put("verified", verified)
        put("duration_ms", durationMs)
        if (error != null) put("error", error)
    }

    companion object {
        fun sanitize(input: String): String {
            // Redact potential API keys or tokens (e.g. sk-..., AIzaSy...)
            return input
                .replace(Regex("AIzaSy[a-zA-Z0-9_-]{10,}"), "[REDACTED_API_KEY]")
                .replace(Regex("sk-[a-zA-Z0-9_-]{10,}"), "[REDACTED_TOKEN]")
        }
    }
}

/**
 * Execution Journal.
 * Maintains a bounded local circular log of the last 100 tool executions for observability and debugging.
 */
object ExecutionJournal {

    private const val MAX_ENTRIES = 100
    private val entries = Collections.synchronizedList(ArrayList<JournalEntry>())

    fun generateExecutionId(): String {
        return "EXEC-" + UUID.randomUUID().toString().take(8).uppercase()
    }

    fun record(entry: JournalEntry) {
        synchronized(entries) {
            entries.add(0, entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(entries.size - 1)
            }
        }
    }

    fun getRecent(limit: Int = 20): List<JournalEntry> {
        synchronized(entries) {
            return entries.take(limit).toList()
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }

    fun toJsonArray(limit: Int = 50): JSONArray {
        val arr = JSONArray()
        synchronized(entries) {
            for (e in entries.take(limit)) {
                arr.put(e.toJsonObject())
            }
        }
        return arr
    }
}
