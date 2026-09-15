package com.pr4nav.jarvis.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rich user persona store for Jarvis companion mode.
 * Auto-learned from usage patterns, conversation topics, and explicit memory.
 * Injected into CONTEXT PACKET (not system prompt) to keep system prompts lean.
 */
object JarvisPersonaStore {

    private const val PREFS = "jarvis_persona"
    private const val KEY = "persona_json"

    data class Persona(
        val name: String = "",
        val age: Int = 0,
        val timezone: String = "Asia/Kolkata",
        val sleepHour: Int = 6,          // hour they typically sleep (24h)
        val wakeHour: Int = 13,          // hour they typically wake
        val goals: List<String> = emptyList(),
        val recentTopics: List<String> = emptyList(),
        val currentFocus: String = "",
        val mood: String = "neutral",    // positive / neutral / stressed
        val preferredTone: String = "terse", // terse / casual / verbose
        val importantPeople: List<String> = emptyList(),
        val lastActiveMs: Long = 0L,
        val appOpenCount: Int = 0
    )

    fun load(context: Context): Persona {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return Persona()
        return try {
            val j = JSONObject(raw)
            Persona(
                name = j.optString("name", ""),
                age = j.optInt("age", 0),
                timezone = j.optString("timezone", "Asia/Kolkata"),
                sleepHour = j.optInt("sleepHour", 6),
                wakeHour = j.optInt("wakeHour", 13),
                goals = j.optJSONArray("goals")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                recentTopics = j.optJSONArray("recentTopics")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                currentFocus = j.optString("currentFocus", ""),
                mood = j.optString("mood", "neutral"),
                preferredTone = j.optString("preferredTone", "terse"),
                importantPeople = j.optJSONArray("importantPeople")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                lastActiveMs = j.optLong("lastActiveMs", 0L),
                appOpenCount = j.optInt("appOpenCount", 0)
            )
        } catch (_: Exception) { Persona() }
    }

    fun save(context: Context, persona: Persona) {
        val j = JSONObject().apply {
            put("name", persona.name)
            put("age", persona.age)
            put("timezone", persona.timezone)
            put("sleepHour", persona.sleepHour)
            put("wakeHour", persona.wakeHour)
            put("goals", JSONArray(persona.goals))
            put("recentTopics", JSONArray(persona.recentTopics))
            put("currentFocus", persona.currentFocus)
            put("mood", persona.mood)
            put("preferredTone", persona.preferredTone)
            put("importantPeople", JSONArray(persona.importantPeople))
            put("lastActiveMs", persona.lastActiveMs)
            put("appOpenCount", persona.appOpenCount)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, j.toString()).apply()
    }

    /** Update last-active timestamp and increment open count on each app open. */
    fun recordAppOpen(context: Context) {
        val p = load(context)
        save(context, p.copy(
            lastActiveMs = System.currentTimeMillis(),
            appOpenCount = p.appOpenCount + 1
        ))
    }

    /** Learn a new topic from a conversation turn. Keeps last 10. */
    fun recordTopic(context: Context, topic: String) {
        if (topic.isBlank() || topic.length < 4) return
        val p = load(context)
        val updated = (listOf(topic.trim().take(60)) + p.recentTopics).distinct().take(10)
        save(context, p.copy(recentTopics = updated))
    }

    /** Set current focus (what user is working on). */
    fun setFocus(context: Context, focus: String) {
        val p = load(context)
        save(context, p.copy(currentFocus = focus.trim().take(80)))
    }

    /** Add a goal. Keeps up to 8. */
    fun addGoal(context: Context, goal: String) {
        val p = load(context)
        val updated = (p.goals + goal.trim().take(60)).distinct().take(8)
        save(context, p.copy(goals = updated))
    }

    /** Build a compact persona block for the context packet. Max ~200 tokens. */
    fun buildPersonaBlock(context: Context): String {
        val p = load(context)
        return buildString {
            if (p.name.isNotBlank()) appendLine("Name: ${p.name}")
            if (p.age > 0) appendLine("Age: ${p.age}")
            if (p.goals.isNotEmpty()) appendLine("Goals: ${p.goals.joinToString("; ")}")
            if (p.currentFocus.isNotBlank()) appendLine("Currently working on: ${p.currentFocus}")
            if (p.recentTopics.isNotEmpty()) appendLine("Recent interests: ${p.recentTopics.take(5).joinToString(", ")}")
            if (p.importantPeople.isNotEmpty()) appendLine("Important people: ${p.importantPeople.take(5).joinToString(", ")}")
            // Infer schedule from persona
            appendLine("Typically active: ${if (p.wakeHour >= 12) "nights/evenings" else "daytime"} (wakes ~${p.wakeHour}:00, sleeps ~${p.sleepHour}:00)")
            appendLine("Preferred tone: ${p.preferredTone}")
        }.trim()
    }
}
