package com.pr4nav.jarvis.opencode.json

import org.json.JSONArray
import org.json.JSONObject

data class OcModelRef(val providerID: String, val modelID: String, val variant: String? = null) {
    override fun toString(): String =
        "$providerID/$modelID" + (variant?.let { ":$it" } ?: "")
}

data class OcSessionInfo(
    val id: String,
    val projectId: String? = null,
    val directory: String? = null,
    val title: String? = null,
    val parentId: String? = null,
    val createdAtMs: Long? = null,
    val updatedAtMs: Long? = null,
    val revertMessageId: String? = null,
    val raw: JSONObject? = null
) {
    val isChild: Boolean get() = parentId != null
}

data class OcMessageInfo(
    val id: String,
    val sessionId: String?,
    val role: String?,
    val agent: String?,
    val providerID: String?,
    val modelID: String?,
    val tokensInput: Long?,
    val tokensOutput: Long?,
    val cost: Double?,
    val completedAtMs: Long?,
    val raw: JSONObject?
)

data class OcPart(
    val id: String?,
    val messageId: String?,
    val sessionId: String?,
    val type: String,
    val text: String?,
    val synthetic: Boolean,
    val toolName: String?,
    val callId: String?,
    val toolStatus: String?,
    val toolInput: JSONObject?,
    val toolTitle: String?,
    val toolMetadata: JSONObject?,
    val raw: JSONObject?
)

data class OcProvider(
    val id: String,
    val name: String,
    val models: List<OcModelInfo>
)

data class OcModelInfo(
    val id: String,
    val name: String?,
    val variants: List<String>
)

data class OcAgent(
    val name: String,
    val mode: String?,
    val hidden: Boolean
)

data class OcProject(
    val id: String,
    val worktree: String?,
    val name: String?,
    val updatedAtMs: Long?
)

data class OcPermissionRequest(
    val requestId: String,
    val sessionId: String?,
    val type: String?,
    val patterns: List<String>,
    val title: String?,
    val metadata: JSONObject?
)

data class OcQuestionOption(
    val label: String,
    val description: String?
)

data class OcQuestion(
    val question: String,
    val header: String?,
    val options: List<OcQuestionOption>,
    val multiple: Boolean
)

data class OcQuestionRequest(
    val requestId: String,
    val sessionId: String?,
    val questions: List<OcQuestion>
)

sealed class OcSessionStatus {
    data class Known(val type: String, val message: String?) : OcSessionStatus()
    object Unknown : OcSessionStatus()

    val isBusy: Boolean get() = this is Known && type == "busy"
    val isError: Boolean get() = this is Known && type == "error"
}

object OcDto {

    fun optStr(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val v = o.opt(k)
                if (v is String) return v
            }
        }
        return null
    }

    fun optLong(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is Number) return v.toLong()
        }
        return null
    }

    fun session(json: JSONObject): OcSessionInfo {
        val time = json.optJSONObject("time")
        return OcSessionInfo(
            id = json.getString("id"),
            projectId = optStr(json, "projectID"),
            directory = optStr(json, "directory"),
            title = optStr(json, "title"),
            parentId = optStr(json, "parentID"),
            createdAtMs = time?.let { optLong(it, "created", "createdAt") },
            updatedAtMs = time?.let { optLong(it, "updated", "updatedAt") },
            revertMessageId = json.optJSONObject("revert")?.let { optStr(it, "messageID") },
            raw = json
        )
    }

    fun sessionList(arr: JSONArray): List<OcSessionInfo> {
        val out = ArrayList<OcSessionInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try {
                out.add(session(o))
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun message(json: JSONObject): OcMessageInfo {
        val tokens = json.optJSONObject("tokens")
        val time = json.optJSONObject("time")
        return OcMessageInfo(
            id = json.getString("id"),
            sessionId = optStr(json, "sessionID"),
            role = optStr(json, "role"),
            agent = optStr(json, "agent"),
            providerID = optStr(json, "providerID"),
            modelID = optStr(json, "modelID"),
            tokensInput = tokens?.let { optLong(it, "input") },
            tokensOutput = tokens?.let { optLong(it, "output") },
            cost = if (json.has("cost") && !json.isNull("cost")) json.optDouble("cost") else null,
            completedAtMs = time?.let { optLong(it, "completed") },
            raw = json
        )
    }

    fun part(json: JSONObject): OcPart {
        val state = json.optJSONObject("state")
        return OcPart(
            id = optStr(json, "id"),
            messageId = optStr(json, "messageID"),
            sessionId = optStr(json, "sessionID"),
            type = optStr(json, "type") ?: "unknown",
            text = optStr(json, "text"),
            synthetic = json.optBoolean("synthetic", false),
            toolName = if (json.optString("type") == "tool") optStr(json, "tool") else null,
            callId = optStr(json, "callID"),
            toolStatus = state?.let { optStr(it, "status") },
            toolInput = state?.optJSONObject("input"),
            toolTitle = state?.let { optStr(it, "title") },
            toolMetadata = state?.optJSONObject("metadata"),
            raw = json
        )
    }

    fun providers(json: JSONObject): List<OcProvider> {
        val arr = when {
            json.has("providers") -> json.optJSONArray("providers")
            else -> null
        } ?: return emptyList()
        val out = ArrayList<OcProvider>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val pid = optStr(p, "id") ?: continue
            val modelsObj = p.optJSONObject("models")
            val models = ArrayList<OcModelInfo>()
            if (modelsObj != null) {
                val keys = modelsObj.keys()
                while (keys.hasNext()) {
                    val mid = keys.next()
                    val m = modelsObj.optJSONObject(mid)
                    val variants = LinkedHashSet<String>()
                    val vObj = m?.optJSONObject("variants")
                    if (vObj != null) {
                        val vk = vObj.keys()
                        while (vk.hasNext()) variants.add(vk.next())
                    }
                    models.add(OcModelInfo(mid, m?.let { optStr(it, "name") }, variants.toList()))
                }
            }
            out.add(OcProvider(pid, optStr(p, "name") ?: pid, models))
        }
        return out
    }

    fun agents(payload: Any?): List<OcAgent> {
        val arr: JSONArray? = when (payload) {
            is JSONArray -> payload
            is JSONObject ->
                if (payload.has("agents")) payload.optJSONArray("agents") else null
            else -> null
        }
        val out = ArrayList<OcAgent>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val name = optStr(a, "name") ?: continue
                out.add(OcAgent(name, optStr(a, "mode"), a.optBoolean("hidden", false)))
            }
        } else if (payload is JSONObject) {
            val keys = payload.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = payload.optJSONObject(k)
                if (a == null) {
                    out.add(OcAgent(k, null, false))
                } else {
                    out.add(OcAgent(k, optStr(a, "mode"), a.optBoolean("hidden", false)))
                }
            }
        }
        return out
    }

    fun projects(payload: Any?): List<OcProject> {
        val arr: JSONArray? = when (payload) {
            is JSONArray -> payload
            is JSONObject -> payload.optJSONArray("projects")
            else -> null
        }
        val out = ArrayList<OcProject>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = optStr(p, "id") ?: continue
                out.add(
                    OcProject(
                        id = id,
                        worktree = optStr(p, "worktree"),
                        name = optStr(p, "name"),
                        updatedAtMs = p.optJSONObject("time")?.let { optLong(it, "updated") }
                    )
                )
            }
        } else if (payload is JSONObject) {
            val keys = payload.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val p = payload.optJSONObject(k) ?: continue
                out.add(
                    OcProject(
                        id = k,
                        worktree = optStr(p, "worktree"),
                        name = optStr(p, "name"),
                        updatedAtMs = p.optJSONObject("time")?.let { optLong(it, "updated") }
                    )
                )
            }
        }
        return out.sortedByDescending { it.updatedAtMs ?: 0L }
    }

    fun permissions(arr: JSONArray): List<OcPermissionRequest> {
        val out = ArrayList<OcPermissionRequest>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val id = optStr(p, "id", "requestID") ?: continue
            val patterns = ArrayList<String>()
            val pa = p.optJSONArray("patterns")
            if (pa != null) for (j in 0 until pa.length()) pa.optString(j)?.let { patterns.add(it) }
            p.optString("pattern")?.let { if (patterns.isEmpty()) patterns.add(it) }
            out.add(
                OcPermissionRequest(
                    requestId = id,
                    sessionId = optStr(p, "sessionID"),
                    type = optStr(p, "type", "permission"),
                    patterns = patterns,
                    title = optStr(p, "title"),
                    metadata = p.optJSONObject("metadata")
                )
            )
        }
        return out
    }

    fun questions(json: JSONObject): OcQuestionRequest? {
        val id = optStr(json, "id", "requestID") ?: return null
        val qArr = json.optJSONArray("questions") ?: return OcQuestionRequest(id, optStr(json, "sessionID"), emptyList())
        val qs = ArrayList<OcQuestion>()
        for (i in 0 until qArr.length()) {
            val q = qArr.optJSONObject(i) ?: continue
            val opts = ArrayList<OcQuestionOption>()
            val oArr = q.optJSONArray("options")
            if (oArr != null) {
                for (j in 0 until oArr.length()) {
                    val o = oArr.optJSONObject(j) ?: continue
                    opts.add(OcQuestionOption(optStr(o, "label") ?: continue, optStr(o, "description")))
                }
            }
            qs.add(
                OcQuestion(
                    question = optStr(q, "question") ?: "",
                    header = optStr(q, "header"),
                    options = opts,
                    multiple = q.optBoolean("multiple", false)
                )
            )
        }
        return OcQuestionRequest(id, optStr(json, "sessionID"), qs)
    }

    fun statusMap(json: JSONObject): Map<String, OcSessionStatus> {
        val out = HashMap<String, OcSessionStatus>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val sid = keys.next()
            val v = json.opt(sid)
            when (v) {
                is JSONObject -> {
                    val t = optStr(v, "type")
                    if (t != null) out[sid] = OcSessionStatus.Known(t, optStr(v, "message"))
                }
                is String -> out[sid] = OcSessionStatus.Known(v, null)
            }
        }
        return out
    }

    fun extractText(parts: List<OcPart>): String {
        return parts.filter { it.type == "text" && !it.synthetic && it.text != null }
            .joinToString("\n") { it.text!! }
    }

    fun messagesPayload(arr: JSONArray): List<Pair<OcMessageInfo, List<OcPart>>> {
        val out = ArrayList<Pair<OcMessageInfo, List<OcPart>>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val infoJson = item.optJSONObject("info") ?: continue
            val partsArr = item.optJSONArray("parts") ?: org.json.JSONArray()
            val parts = ArrayList<OcPart>()
            for (j in 0 until partsArr.length()) {
                val pj = partsArr.optJSONObject(j) ?: continue
                parts.add(part(pj))
            }
            try {
                out.add(message(infoJson) to parts)
            } catch (_: Exception) {
            }
        }
        return out
    }
}
