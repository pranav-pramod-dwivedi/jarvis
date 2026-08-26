package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcAgent
import com.pr4nav.jarvis.opencode.json.OcDto
import com.pr4nav.jarvis.opencode.json.OcModelInfo
import com.pr4nav.jarvis.opencode.json.OcModelRef
import com.pr4nav.jarvis.opencode.json.OcMessageInfo
import com.pr4nav.jarvis.opencode.json.OcPart
import com.pr4nav.jarvis.opencode.json.OcPermissionRequest
import com.pr4nav.jarvis.opencode.json.OcProject
import com.pr4nav.jarvis.opencode.json.OcProvider
import com.pr4nav.jarvis.opencode.json.OcQuestionRequest
import com.pr4nav.jarvis.opencode.json.OcSessionInfo
import com.pr4nav.jarvis.opencode.json.OcSessionStatus
import com.pr4nav.jarvis.opencode.transport.OpenCodeHttp
import org.json.JSONArray
import org.json.JSONObject

class OpenCodeClient(configProvider: () -> OpenCodeConfig) {

    private val http = OpenCodeHttp(configProvider)

    data class Health(val healthy: Boolean, val version: String?)

    sealed class PromptPart {
        data class Text(val text: String) : PromptPart()
        data class FileUrl(val url: String, val mime: String?) : PromptPart()

        fun toJson(): JSONObject = when (this) {
            is Text -> JSONObject().put("type", "text").put("text", text)
            is FileUrl -> {
                val o = JSONObject().put("type", "file").put("url", url)
                if (mime != null) o.put("mime", mime)
                o
            }
        }
    }

    enum class PermissionDecision(val wire: String) {
        ONCE("once"), ALWAYS("always"), REJECT("reject")
    }

    fun health(): OcResult<Health> = ocTry(TAG) {
        // Try modern then legacy health endpoints; web mode serves HTML at / but still exposes API
        val candidates = listOf("/global/health", "/api/health", "/health")
        var lastErr: Exception? = null
        for (path in candidates) {
            try {
                val raw = http.getJson(path).getOrThrow()
                // HTML fallback (web root) is not health — skip
                if (raw.trimStart().startsWith("<!doctype", ignoreCase = true) || raw.trimStart().startsWith("<html", ignoreCase = true)) continue
                val obj = JSONObject(raw)
                return@ocTry Health(obj.optBoolean("healthy", true), OcDto.optStr(obj, "version"))
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: OpenCodeException.unavailable("health probe failed")
    }

    fun sessions(directory: String? = null): OcResult<List<OcSessionInfo>> = ocTry(TAG) {
        val arr = http.parseArray(http.getJson("/session", q("directory" to directory))).getOrThrow()
        OcDto.sessionList(arr)
    }

    fun session(id: String, directory: String? = null): OcResult<OcSessionInfo> = ocTry(TAG) {
        val obj = http.parseObject(http.getJson("/session/$id", q("directory" to directory))).getOrThrow()
        OcDto.session(obj)
    }

    fun createSession(directory: String, title: String? = null): OcResult<OcSessionInfo> = ocTry(TAG) {
        val body = JSONObject()
        if (title != null) body.put("title", title)
        val obj = http.parseObject(http.postJson("/session", body, q("directory" to directory))).getOrThrow()
        OcDto.session(obj)
    }

    fun renameSession(id: String, title: String, directory: String? = null): OcResult<OcSessionInfo> =
        ocTry(TAG) {
            val obj = http.parseObject(
                http.patchJson("/session/$id", JSONObject().put("title", title), q("directory" to directory))
            ).getOrThrow()
            OcDto.session(obj)
        }

    fun deleteSession(id: String, directory: String? = null): OcResult<Unit> = ocTry(TAG) {
        http.deleteJson("/session/$id", q("directory" to directory)).getOrThrow()
    }

    fun abortSession(id: String, directory: String? = null): OcResult<Boolean> = ocTry(TAG) {
        val raw = http.postJson("/session/$id/abort", null, q("directory" to directory))
        when (raw) {
            is OpenCodeHttp.RawResult.Ok -> raw.body.trim() == "true"
            is OpenCodeHttp.RawResult.Failed -> throw raw.error
        }
    }

    fun forkSession(
        id: String,
        messageId: String? = null,
        directory: String? = null
    ): OcResult<OcSessionInfo> = ocTry(TAG) {
        val body = if (messageId != null) JSONObject().put("messageID", messageId) else null
        val obj = http.parseObject(
            http.postJson("/session/$id/fork", body, q("directory" to directory))
        ).getOrThrow()
        OcDto.session(obj)
    }

    fun statusMap(directory: String? = null): OcResult<Map<String, OcSessionStatus>> = ocTry(TAG) {
        val obj = http.parseObject(http.getJson("/session/status", q("directory" to directory))).getOrThrow()
        OcDto.statusMap(obj)
    }

    fun messages(id: String, limit: Int? = null, directory: String? = null):
        OcResult<List<Pair<OcMessageInfo, List<OcPart>>>> = ocTry(TAG) {
        val extra = ArrayList<Pair<String, String?>>()
        extra.add("directory" to directory)
        if (limit != null) extra.add("limit" to limit.toString())
        val arr = http.parseArray(http.getJson("/session/$id/message", extra)).getOrThrow()
        OcDto.messagesPayload(arr)
    }

    fun diff(sessionId: String, directory: String? = null): OcResult<JSONArray> = ocTry(TAG) {
        http.parseArray(http.getJson("/session/$sessionId/diff", q("directory" to directory))).getOrThrow()
    }

    fun children(sessionId: String, directory: String? = null): OcResult<List<OcSessionInfo>> = ocTry(TAG) {
        val payload = http.getJson("/session/$sessionId/children", q("directory" to directory))
        when (val parsed = http.parseArray(payload)) {
            is OpenCodeHttp.OcParsed.Ok -> OcDto.sessionList(parsed.value)
            is OpenCodeHttp.OcParsed.Failed -> throw parsed.error
        }
    }

    fun promptAsync(
        sessionId: String,
        parts: List<PromptPart>,
        directory: String?,
        agent: String? = null,
        model: OcModelRef? = null,
        noReplyMode: Boolean = false
    ): OcResult<JSONObject> = ocTry(TAG) {
        if (parts.isEmpty()) {
            throw OpenCodeException(
                OpenCodeException.Code.BAD_REQUEST,
                "prompt needs at least one part"
            )
        }
        val body = JSONObject()
        body.put("parts", JSONArray().also { arr -> parts.forEach { arr.put(it.toJson()) } })
        if (!agent.isNullOrBlank()) body.put("agent", agent)
        if (model != null) {
            body.put(
                "model",
                JSONObject().put("providerID", model.providerID).put("modelID", model.modelID)
            )
        }
        if (model?.variant != null) body.put("variant", model.variant)
        if (noReplyMode) body.put("noReply", true)
        val res = http.postJson("/session/$sessionId/prompt_async", body, q("directory" to directory))
        when (res) {
            is OpenCodeHttp.RawResult.Ok ->
                if (res.body.isBlank()) JSONObject()
                else JSONObject(res.body)
            is OpenCodeHttp.RawResult.Failed -> throw res.error
        }
    }

    fun pendingPermissions(directory: String? = null): OcResult<List<OcPermissionRequest>> = ocTry(TAG) {
        val payload = http.getJson("/permission", q("directory" to directory))
        val arr = when {
            payload is OpenCodeHttp.RawResult.Ok && payload.body.trimStart().startsWith("[") ->
                http.parseArray(payload).getOrThrow()
            else -> {
                val obj = http.parseObject(payload).getOrThrow()
                obj.optJSONArray("permissions")
                    ?: obj.optJSONArray("requests")
                    ?: JSONArray()
            }
        }
        OcDto.permissions(arr)
    }

    fun replyToPermission(
        requestId: String,
        decision: PermissionDecision,
        directory: String? = null,
        message: String? = null
    ): OcResult<Unit> = ocTry(TAG) {
        val body = JSONObject().put("reply", decision.wire)
        if (message != null) body.put("message", message)
        http.postJson("/permission/$requestId/reply", body, q("directory" to directory)).getOrThrow()
    }

    fun pendingQuestions(directory: String? = null): OcResult<List<OcQuestionRequest>> = ocTry(TAG) {
        val payload = http.getJson("/question", q("directory" to directory))
        val out = ArrayList<OcQuestionRequest>()
        val body = when (payload) {
            is OpenCodeHttp.RawResult.Ok -> payload.body
            is OpenCodeHttp.RawResult.Failed -> throw payload.error
        }
        if (body.trimStart().startsWith("[")) {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { OcDto.questions(it)?.let { r -> out.add(r) } }
            }
        } else if (body.isNotBlank()) {
            OcDto.questions(JSONObject(body))?.let { out.add(it) }
        }
        out
    }

    fun replyToQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null
    ): OcResult<Unit> = ocTry(TAG) {
        val body = JSONObject().put(
            "answers",
            JSONArray().also { outer ->
                answers.forEach { inner ->
                    outer.put(JSONArray().also { ia -> inner.forEach { ia.put(it) } })
                }
            }
        )
        http.postJson("/question/$requestId/reply", body, q("directory" to directory)).getOrThrow()
    }

    fun rejectQuestion(requestId: String, directory: String? = null): OcResult<Unit> = ocTry(TAG) {
        http.postJson("/question/$requestId/reject", null, q("directory" to directory)).getOrThrow()
    }

    fun projects(): OcResult<List<OcProject>> = ocTry(TAG) {
        val payload = http.getJson("/project")
        val body = when (payload) {
            is OpenCodeHttp.RawResult.Ok -> payload.body
            is OpenCodeHttp.RawResult.Failed -> throw payload.error
        }
        if (body.trimStart().startsWith("[")) {
            OcDto.projects(JSONArray(body))
        } else {
            OcDto.projects(JSONObject(body))
        }
    }

    fun currentProject(): OcResult<OcProject?> = ocTry(TAG) {
        val parsed = http.parseObject(http.getJson("/project/current"))
        when (parsed) {
            is OpenCodeHttp.OcParsed.Ok ->
                OcProject(
                    id = OcDto.optStr(parsed.value, "id") ?: "",
                    worktree = OcDto.optStr(parsed.value, "worktree"),
                    name = OcDto.optStr(parsed.value, "name"),
                    updatedAtMs = parsed.value.optJSONObject("time")?.let { OcDto.optLong(it, "updated") }
                )
            is OpenCodeHttp.OcParsed.Failed ->
                if (parsed.error.httpStatus == 404 || parsed.error.code == OpenCodeException.Code.NOT_FOUND) null
                else throw parsed.error
        }
    }

    fun providers(): OcResult<List<OcProvider>> = ocTry(TAG) {
        val obj = http.parseObject(http.getJson("/config/providers")).getOrThrow()
        OcDto.providers(obj)
    }

    fun modelsOf(providerId: String): OcResult<List<OcModelInfo>> = providers().map { list ->
        list.firstOrNull { it.id == providerId }?.models ?: emptyList()
    }

    fun agents(): OcResult<List<OcAgent>> = ocTry(TAG) {
        val payload = http.getJson("/agent")
        val body = when (payload) {
            is OpenCodeHttp.RawResult.Ok -> payload.body
            is OpenCodeHttp.RawResult.Failed -> throw payload.error
        }
        if (body.trimStart().startsWith("[")) {
            OcDto.agents(JSONArray(body))
        } else {
            OcDto.agents(JSONObject(body))
        }
    }

    fun commands(): OcResult<JSONArray> = ocTry(TAG) {
        http.parseArray(http.getJson("/command")).getOrThrow()
    }

    fun skills(): OcResult<JSONArray> = ocTry(TAG) {
        http.parseArray(http.getJson("/skill")).getOrThrow()
    }

    fun pathInfo(): OcResult<JSONObject> = ocTry(TAG) {
        http.parseObject(http.getJson("/path")).getOrThrow()
    }

    private fun q(vararg pairs: Pair<String, String?>): List<Pair<String, String?>> = pairs.toList()

    companion object {
        const val TAG = "Client"
    }
}
