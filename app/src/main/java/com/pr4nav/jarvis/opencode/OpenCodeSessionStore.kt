package com.pr4nav.jarvis.opencode

import org.json.JSONArray
import org.json.JSONObject

interface OcKvStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class InMemoryKvStore : OcKvStore {
    private val map = HashMap<String, String>()
    @Synchronized
    override fun getString(key: String): String? = map[key]
    @Synchronized
    override fun putString(key: String, value: String) {
        map[key] = value
    }
    @Synchronized
    override fun remove(key: String) {
        map.remove(key)
    }
}

class PrefsKvStore(private val context: android.content.Context, name: String = "opencode_store") :
    OcKvStore {
    private val prefs by lazy {
        context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

object OpenCodeSessionStore {

    data class SessionRecord(
        val sessionId: String,
        val directory: String,
        val title: String? = null,
        val label: String? = null,
        val modelProviderId: String? = null,
        val modelId: String? = null,
        val variant: String? = null,
        val agent: String? = null,
        val background: Boolean = false,
        val archived: Boolean = false,
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
        val lastActivityAtMs: Long = System.currentTimeMillis()
    )

    const val KEY_REGISTRY = "session_registry_v1"
    const val KEY_CURRENT = "current_session_id"
    const val KEY_SERVER_BASE_URL = "server_base_url"
    const val KEY_SERVER_OWNED = "server_owned"
    const val KEY_SERVER_PORT = "server_port"
    const val KEY_SERVER_USERNAME = "server_username"
    const val KEY_SERVER_PASSWORD = "server_password"
    const val KEY_CURRENT_AGENT = "current_agent"
    const val KEY_CURRENT_MODEL = "current_model"
    const val KEY_KNOWN_PROJECTS = "known_projects"
    const val KEY_AUTO_START = "auto_start_server"

    fun loadRegistry(kv: OcKvStore): MutableList<SessionRecord> {
        val raw = kv.getString(KEY_REGISTRY) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<SessionRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("sessionId")
                if (id.isBlank()) continue
                out.add(
                    SessionRecord(
                        sessionId = id,
                        directory = o.optString("directory"),
                        title = o.optStringOrNull("title"),
                        label = o.optStringOrNull("label"),
                        modelProviderId = o.optStringOrNull("modelProviderId"),
                        modelId = o.optStringOrNull("modelId"),
                        variant = o.optStringOrNull("variant"),
                        agent = o.optStringOrNull("agent"),
                        background = o.optBoolean("background", false),
                        archived = o.optBoolean("archived", false),
                        createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
                        updatedAtMs = o.optLong("updatedAtMs", 0L),
                        lastActivityAtMs = o.optLong("lastActivityAtMs", 0L)
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveRegistry(kv: OcKvStore, records: List<SessionRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject()
                    .put("sessionId", r.sessionId)
                    .put("directory", r.directory)
                    .putOpt("title", r.title)
                    .putOpt("label", r.label)
                    .putOpt("modelProviderId", r.modelProviderId)
                    .putOpt("modelId", r.modelId)
                    .putOpt("variant", r.variant)
                    .putOpt("agent", r.agent)
                    .put("background", r.background)
                    .put("archived", r.archived)
                    .put("createdAtMs", r.createdAtMs)
                    .put("updatedAtMs", r.updatedAtMs)
                    .put("lastActivityAtMs", r.lastActivityAtMs)
            )
        }
        kv.putString(KEY_REGISTRY, arr.toString())
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key)
        return if (v.isBlank()) null else v
    }
}
