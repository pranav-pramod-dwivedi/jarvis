package com.pr4nav.jarvis.session

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SessionType {
    AGENT_CHAT,
    VOICE_CHAT,
    AGY_CODING
}

data class SessionMessage(
    val id: String = "msg_${System.currentTimeMillis()}_${(100..999).random()}",
    val sender: String, // "user", "agent", "system", "tool"
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val steps: List<String> = emptyList(),
    val isSuccess: Boolean = true,
    val toolCall: String? = null,
    val codeDiff: String? = null
)

data class JarvisSession(
    val id: String,
    val title: String, // Named by Date and Time of creation ONLY (e.g. "01 Sep 2026, 01:36:15 AM")
    val type: SessionType,
    val createdAtMs: Long,
    var lastUsedMs: Long,
    val messages: MutableList<SessionMessage> = mutableListOf(),
    var workingDir: String = "/sdcard",
    var modelUsed: String = "Qwen 2.5 / Gemini Flash"
)

/**
 * Unified Thread-Safe Persistent Session Manager for JARVIS.
 *
 * Requirements:
 * - Sessions named strictly by Date and Time of creation.
 * - Always sorted by Last Used First (lastUsedMs descending).
 * - Full multi-turn conversation and coding execution history persistence.
 */
object JarvisSessionManager {

    private const val TAG = "JarvisSessionManager"
    private const val SESSIONS_DIR = "jarvis_sessions"
    private const val ACTIVE_PREFS = "jarvis_active_sessions"

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, SESSIONS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun generateTitle(createdAtMs: Long): String {
        return dateFormat.format(Date(createdAtMs))
    }

    @Synchronized
    fun createSession(context: Context, type: SessionType, workingDir: String = "/sdcard"): JarvisSession {
        val now = System.currentTimeMillis()
        val typePrefix = when (type) {
            SessionType.AGENT_CHAT -> "chat"
            SessionType.VOICE_CHAT -> "voice"
            SessionType.AGY_CODING -> "agy"
        }
        val id = "${typePrefix}_$now"
        val title = generateTitle(now)

        val session = JarvisSession(
            id = id,
            title = title,
            type = type,
            createdAtMs = now,
            lastUsedMs = now,
            workingDir = workingDir
        )

        saveSession(context, session)
        setActiveSessionId(context, type, session.id)
        Log.i(TAG, "Created new ${type.name} session: \"${session.title}\" (ID: ${session.id})")
        return session
    }

    @Synchronized
    fun getActiveSession(context: Context, type: SessionType): JarvisSession {
        val prefs = context.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE)
        val activeId = prefs.getString("active_${type.name}", null)

        if (activeId != null) {
            val loaded = loadSession(context, activeId)
            if (loaded != null) return loaded
        }

        // Fallback to most recently used session of this type
        val existing = listSessions(context, type)
        if (existing.isNotEmpty()) {
            val latest = existing.first() // Already sorted Last Used First
            setActiveSessionId(context, type, latest.id)
            return latest
        }

        // Create initial session
        return createSession(context, type)
    }

    @Synchronized
    fun setActiveSessionId(context: Context, type: SessionType, sessionId: String) {
        context.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("active_${type.name}", sessionId)
            .apply()
    }

    @Synchronized
    fun listSessions(context: Context, filterType: SessionType? = null): List<JarvisSession> {
        val dir = getStorageDir(context)
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        val sessions = ArrayList<JarvisSession>()

        for (file in files) {
            try {
                val jsonStr = file.readText()
                val session = parseSession(JSONObject(jsonStr))
                if (session != null) {
                    if (filterType == null || session.type == filterType) {
                        sessions.add(session)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading session from ${file.name}: ${e.message}")
            }
        }

        // Sort by LAST USED FIRST
        return sessions.sortedByDescending { it.lastUsedMs }
    }

    @Synchronized
    fun loadSession(context: Context, sessionId: String): JarvisSession? {
        val file = File(getStorageDir(context), "$sessionId.json")
        if (!file.exists()) return null
        return try {
            val jsonStr = file.readText()
            parseSession(JSONObject(jsonStr))
        } catch (e: Exception) {
            Log.e(TAG, "Error loading session $sessionId: ${e.message}", e)
            null
        }
    }

    @Synchronized
    fun saveSession(context: Context, session: JarvisSession) {
        val file = File(getStorageDir(context), "${session.id}.json")
        try {
            val json = serializeSession(session)
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving session ${session.id}: ${e.message}", e)
        }
    }

    @Synchronized
    fun appendMessage(context: Context, session: JarvisSession, message: SessionMessage) {
        session.messages.add(message)
        session.lastUsedMs = System.currentTimeMillis()
        saveSession(context, session)
    }

    @Synchronized
    fun deleteSession(context: Context, sessionId: String): Boolean {
        val file = File(getStorageDir(context), "$sessionId.json")
        return file.delete()
    }

    @Synchronized
    fun clearAllSessions(context: Context, type: SessionType? = null) {
        val dir = getStorageDir(context)
        val files = dir.listFiles { f -> f.extension == "json" } ?: return
        for (file in files) {
            if (type == null) {
                file.delete()
            } else {
                try {
                    val s = parseSession(JSONObject(file.readText()))
                    if (s?.type == type) {
                        file.delete()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun serializeSession(s: JarvisSession): JSONObject {
        val obj = JSONObject()
        obj.put("id", s.id)
        obj.put("title", s.title)
        obj.put("type", s.type.name)
        obj.put("createdAtMs", s.createdAtMs)
        obj.put("lastUsedMs", s.lastUsedMs)
        obj.put("workingDir", s.workingDir)
        obj.put("modelUsed", s.modelUsed)

        val msgArr = JSONArray()
        for (m in s.messages) {
            val mObj = JSONObject()
            mObj.put("id", m.id)
            mObj.put("sender", m.sender)
            mObj.put("text", m.text)
            mObj.put("timestampMs", m.timestampMs)
            mObj.put("isSuccess", m.isSuccess)

            val stepsArr = JSONArray()
            m.steps.forEach { stepsArr.put(it) }
            mObj.put("steps", stepsArr)

            if (m.toolCall != null) mObj.put("toolCall", m.toolCall)
            if (m.codeDiff != null) mObj.put("codeDiff", m.codeDiff)
            msgArr.put(mObj)
        }
        obj.put("messages", msgArr)
        return obj
    }

    private fun parseSession(obj: JSONObject): JarvisSession? {
        val id = obj.optString("id")
        if (id.isBlank()) return null

        val createdAt = obj.optLong("createdAtMs", System.currentTimeMillis())
        val title = obj.optString("title").ifBlank { generateTitle(createdAt) }
        val typeStr = obj.optString("type", SessionType.AGENT_CHAT.name)
        val type = try { SessionType.valueOf(typeStr) } catch (_: Exception) { SessionType.AGENT_CHAT }
        val lastUsed = obj.optLong("lastUsedMs", createdAt)
        val workingDir = obj.optString("workingDir", "/sdcard")
        val modelUsed = obj.optString("modelUsed", "Qwen 2.5 / Gemini Flash")

        val messages = ArrayList<SessionMessage>()
        val msgArr = obj.optJSONArray("messages")
        if (msgArr != null) {
            for (i in 0 until msgArr.length()) {
                val mObj = msgArr.optJSONObject(i) ?: continue
                val steps = ArrayList<String>()
                val sArr = mObj.optJSONArray("steps")
                if (sArr != null) {
                    for (j in 0 until sArr.length()) {
                        steps.add(sArr.optString(j))
                    }
                }
                messages.add(
                    SessionMessage(
                        id = mObj.optString("id"),
                        sender = mObj.optString("sender", "agent"),
                        text = mObj.optString("text", ""),
                        timestampMs = mObj.optLong("timestampMs", System.currentTimeMillis()),
                        steps = steps,
                        isSuccess = mObj.optBoolean("isSuccess", true),
                        toolCall = mObj.optString("toolCall").takeIf { it.isNotBlank() },
                        codeDiff = mObj.optString("codeDiff").takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        return JarvisSession(
            id = id,
            title = title,
            type = type,
            createdAtMs = createdAt,
            lastUsedMs = lastUsed,
            messages = messages.toMutableList(),
            workingDir = workingDir,
            modelUsed = modelUsed
        )
    }
}
