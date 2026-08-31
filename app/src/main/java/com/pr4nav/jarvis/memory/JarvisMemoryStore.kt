package com.pr4nav.jarvis.memory

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * JARVIS Long-term Memory & Knowledge Store
 * Handles identity, preferences, facts, people, projects,
 * semantic search, retrieval, updates, and forgetting.
 */
object JarvisMemoryStore {

    private const val PREFS_NAME = "jarvis_memory_store"
    private const val KEY_MEMORIES = "memories_json"
    private const val KEY_PREFERENCES = "user_preferences_json"

    data class MemoryItem(
        val key: String,
        val value: String,
        val category: String = "general",
        val timestamp: Long = System.currentTimeMillis()
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun remember(context: Context, key: String, value: String, category: String = "general"): MemoryItem {
        val items = getAll(context).toMutableList()
        // Remove existing key if present
        items.removeAll { it.key.equals(key.trim(), ignoreCase = true) }
        val item = MemoryItem(key.trim(), value.trim(), category)
        items.add(0, item)
        saveAll(context, items)
        return item
    }

    fun forget(context: Context, keyOrQuery: String): Boolean {
        val items = getAll(context).toMutableList()
        val before = items.size
        val q = keyOrQuery.trim().lowercase(Locale.US)
        items.removeAll { it.key.lowercase(Locale.US) == q || it.key.lowercase(Locale.US).contains(q) }
        saveAll(context, items)
        return items.size < before
    }

    fun recall(context: Context, query: String): List<MemoryItem> {
        val q = query.trim().lowercase(Locale.US)
        val all = getAll(context)
        return all.filter { item ->
            item.key.lowercase(Locale.US).contains(q) ||
            item.value.lowercase(Locale.US).contains(q) ||
            q.contains(item.key.lowercase(Locale.US))
        }
    }

    fun getAll(context: Context): List<MemoryItem> {
        val raw = getPrefs(context).getString(KEY_MEMORIES, "[]") ?: "[]"
        val list = mutableListOf<MemoryItem>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    MemoryItem(
                        key = obj.optString("key"),
                        value = obj.optString("value"),
                        category = obj.optString("category", "general"),
                        timestamp = obj.optLong("timestamp", 0)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveAll(context: Context, items: List<MemoryItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
                .put("key", item.key)
                .put("value", item.value)
                .put("category", item.category)
                .put("timestamp", item.timestamp)
            array.put(obj)
        }
        getPrefs(context).edit().putString(KEY_MEMORIES, array.toString()).apply()
    }

    /**
     * Automatic Extraction Engine: Detects natural memory statements and saves them.
     * e.g. "Remember that my secret code is banana ate cow"
     * e.g. "Remember my doctor appointment is on Tuesday"
     */
    fun tryExtractAndRemember(context: Context, input: String): MemoryItem? {
        val raw = input.trim()
        val lower = raw.lowercase(Locale.US)

        // 1. "Remember (that) my [X] is [Y]" or "Remember [X] is [Y]"
        val p1 = Pattern.compile("^(?:please )?remember (?:that )?(?:my )?(.*?) (?:is|are|was) (.*)$", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (p1.find()) {
            val key = p1.group(1)?.trim() ?: return null
            val value = p1.group(2)?.trim() ?: return null
            return remember(context, key, value, "fact")
        }

        // 2. "My [X] is [Y], remember it"
        val p2 = Pattern.compile("^(?:my )?(.*?) (?:is|are|was) (.*?)(?:, remember it| remember it)?$", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (p2.find() && lower.contains("remember")) {
            val key = p2.group(1)?.trim() ?: return null
            val value = p2.group(2)?.replace(Regex("(?i),?\\s*remember it.*"), "")?.trim() ?: return null
            return remember(context, key, value, "fact")
        }

        // 3. "Remember this: [X]"
        val p3 = Pattern.compile("^(?:please )?remember this(?::| -)? (.*)$", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (p3.find()) {
            val value = p3.group(1)?.trim() ?: return null
            val key = if (value.length > 25) value.take(25) + "..." else value
            return remember(context, key, value, "note")
        }

        return null
    }

    /**
     * Automatic Retrieval Engine: Resolves questions about stored memories.
     * e.g. "What was my code?" / "What is my secret code?" / "Do you remember X?"
     */
    fun tryRetrieve(context: Context, input: String): String? {
        val raw = input.trim()
        val lower = raw.lowercase(Locale.US)

        if (lower.startsWith("what is my ") || lower.startsWith("what was my ") ||
            lower.startsWith("what's my ") || lower.startsWith("do you remember my ") ||
            lower.startsWith("do you remember ") || lower.startsWith("who is ")) {

            val target = raw.replace(Regex("^(?i)(what is my|what was my|what's my|do you remember my|do you remember|who is)\\s*"), "")
                            .replace(Regex("[?!.]+$"), "").trim()

            if (target.isNotEmpty()) {
                val matches = recall(context, target)
                val best = matches.firstOrNull()
                if (best != null) {
                    return "🧠 Memory recalled: Your ${best.key} is \"${best.value}\"."
                }
            }
        }
        return null
    }

    /**
     * Automatic Forget Engine:
     * e.g. "Forget my code" / "Forget that my password is X"
     */
    fun tryForget(context: Context, input: String): String? {
        val lower = input.trim().lowercase(Locale.US)
        if (lower.startsWith("forget ") || lower.startsWith("delete memory ")) {
            val target = input.trim().replace(Regex("^(?i)(forget that my|forget my|forget that|forget|delete memory of|delete memory)\\s*"), "")
                                    .replace(Regex("[?!.]+$"), "").trim()
            if (target.isNotEmpty()) {
                val deleted = forget(context, target)
                return if (deleted) "🗑️ Forgotten: Memory of \"$target\" has been erased."
                       else "I couldn't find any stored memory matching \"$target\"."
            }
        }
        return null
    }
}
