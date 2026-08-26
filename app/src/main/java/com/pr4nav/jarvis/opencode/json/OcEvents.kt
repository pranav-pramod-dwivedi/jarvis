package com.pr4nav.jarvis.opencode.json

import org.json.JSONObject

sealed class OcEvent {
    abstract val directory: String?

    data class ServerConnected(override val directory: String?) : OcEvent()
    data class Heartbeat(override val directory: String?) : OcEvent()

    data class MessageUpdated(
        override val directory: String?,
        val message: OcMessageInfo
    ) : OcEvent()

    data class PartUpdated(
        override val directory: String?,
        val part: OcPart
    ) : OcEvent()

    data class PartDelta(
        override val directory: String?,
        val sessionId: String?,
        val messageId: String?,
        val partId: String?,
        val type: String?,
        val delta: String
    ) : OcEvent()

    data class SessionCreated(
        override val directory: String?,
        val session: OcSessionInfo
    ) : OcEvent()

    data class SessionUpdated(
        override val directory: String?,
        val session: OcSessionInfo
    ) : OcEvent()

    data class SessionIdle(
        override val directory: String?,
        val sessionId: String
    ) : OcEvent()

    data class SessionError(
        override val directory: String?,
        val sessionId: String?,
        val errorName: String?,
        val errorMessage: String?
    ) : OcEvent()

    data class SessionDiff(
        override val directory: String?,
        val sessionId: String?,
        val files: List<DiffFile>
    ) : OcEvent() {
        data class DiffFile(val file: String, val additions: Long, val deletions: Long)
    }

    data class SessionStatus(
        override val directory: String?,
        val sessionId: String?,
        val statusType: String?,
        val message: String?
    ) : OcEvent()

    data class PermissionAsked(
        override val directory: String?,
        val request: OcPermissionRequest
    ) : OcEvent()

    data class PermissionReplied(
        override val directory: String?,
        val sessionId: String?,
        val requestId: String,
        val response: String?
    ) : OcEvent()

    data class QuestionAsked(
        override val directory: String?,
        val request: OcQuestionRequest
    ) : OcEvent()

    data class QuestionReplied(
        override val directory: String?,
        val sessionId: String?,
        val requestId: String
    ) : OcEvent()

    data class QuestionRejected(
        override val directory: String?,
        val sessionId: String?,
        val requestId: String
    ) : OcEvent()

    data class Unknown(
        override val directory: String?,
        val type: String,
        val properties: JSONObject?
    ) : OcEvent()
}

object OcEvents {

    fun decode(root: JSONObject): OcEvent {
        var dir: String? = null
        var payload = root
        if (root.has("payload") && root.optJSONObject("payload") != null) {
            dir = OcDto.optStr(root, "directory")
            payload = root.getJSONObject("payload")
        }
        val type = OcDto.optStr(payload, "type") ?: return OcEvent.Unknown(dir, "(missing-type)", payload)
        val props = payload.optJSONObject("properties") ?: JSONObject()
        try {
            return decodeTyped(dir, type, props)
        } catch (_: Exception) {
            return OcEvent.Unknown(dir, type, props)
        }
    }

    private fun decodeTyped(dir: String?, type: String, p: JSONObject): OcEvent {
        return when (type) {
            "server.connected" -> OcEvent.ServerConnected(dir ?: OcDto.optStr(p, "directory"))
            "server.heartbeat" -> OcEvent.Heartbeat(dir)

            "message.updated" -> {
                val info = p.optJSONObject("info")
                    ?: return OcEvent.Unknown(dir, type, p)
                OcEvent.MessageUpdated(dir, OcDto.message(info))
            }

            "message.part.updated" -> {
                val partJson = p.optJSONObject("part")
                    ?: return OcEvent.Unknown(dir, type, p)
                OcEvent.PartUpdated(dir, OcDto.part(partJson))
            }

            "message.part.delta" -> OcEvent.PartDelta(
                dir,
                OcDto.optStr(p, "sessionID"),
                OcDto.optStr(p, "messageID"),
                OcDto.optStr(p, "partID"),
                OcDto.optStr(p, "type", "field"),
                OcDto.optStr(p, "delta") ?: ""
            )

            "session.created" -> sessionEvt(dir, type, p) { s, d -> OcEvent.SessionCreated(d, s) }
            "session.updated" -> sessionEvt(dir, type, p) { s, d -> OcEvent.SessionUpdated(d, s) }

            "session.idle" -> OcEvent.SessionIdle(
                dir,
                OcDto.optStr(p, "sessionID") ?: ""
            )

            "session.error" -> {
                val errObj = p.optJSONObject("error")
                OcEvent.SessionError(
                    dir,
                    OcDto.optStr(p, "sessionID") ?: errObj?.let { OcDto.optStr(it, "sessionID") },
                    errObj?.let { OcDto.optStr(it, "name") } ?: OcDto.optStr(p, "name"),
                    errObj?.let { OcDto.optStr(it, "message", "data") }
                        ?: OcDto.optStr(p, "message")
                )
            }

            "session.diff" -> {
                val arr = p.optJSONArray("diff")
                val files = ArrayList<OcEvent.SessionDiff.DiffFile>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        files.add(
                            OcEvent.SessionDiff.DiffFile(
                                OcDto.optStr(f, "file") ?: continue,
                                f.optLong("additions"),
                                f.optLong("deletions")
                            )
                        )
                    }
                }
                OcEvent.SessionDiff(dir, OcDto.optStr(p, "sessionID"), files)
            }

            "session.status" -> OcEvent.SessionStatus(
                dir,
                OcDto.optStr(p, "sessionID"),
                p.optJSONObject("status")?.let { OcDto.optStr(it, "type") } ?: OcDto.optStr(p, "status"),
                p.optJSONObject("status")?.let { OcDto.optStr(it, "message") } ?: OcDto.optStr(p, "message")
            )

            "permission.asked" -> {
                val req = permissionFromProps(p)
                    ?: return OcEvent.Unknown(dir, type, p)
                OcEvent.PermissionAsked(dir, req)
            }

            "permission.replied" -> OcEvent.PermissionReplied(
                dir,
                OcDto.optStr(p, "sessionID"),
                OcDto.optStr(p, "requestID", "id") ?: "",
                OcDto.optStr(p, "response")
            )

            "question.asked" -> {
                val req = OcDto.questions(p)
                    ?: return OcEvent.Unknown(dir, type, p)
                OcEvent.QuestionAsked(dir, req)
            }

            "question.replied" -> OcEvent.QuestionReplied(
                dir,
                OcDto.optStr(p, "sessionID"),
                OcDto.optStr(p, "requestID", "id") ?: ""
            )

            "question.rejected" -> OcEvent.QuestionRejected(
                dir,
                OcDto.optStr(p, "sessionID"),
                OcDto.optStr(p, "requestID", "id") ?: ""
            )

            else -> OcEvent.Unknown(dir, type, p)
        }
    }

    private inline fun sessionEvt(
        dir: String?,
        type: String,
        p: JSONObject,
        build: (OcSessionInfo, String?) -> OcEvent
    ): OcEvent {
        val info = p.optJSONObject("info")
            ?: return OcEvent.Unknown(dir, type, p)
        return build(OcDto.session(info), dir)
    }

    private fun permissionFromProps(p: JSONObject): OcPermissionRequest? {
        if (p.has("patterns") || p.has("pattern") || p.has("permission")) {
            val id = OcDto.optStr(p, "id", "requestID") ?: return null
            val patterns = ArrayList<String>()
            p.optJSONArray("patterns")?.let { pa ->
                for (j in 0 until pa.length()) pa.optString(j)?.let { patterns.add(it) }
            }
            p.optString("pattern")?.let { if (patterns.isEmpty()) patterns.add(it) }
            return OcPermissionRequest(
                requestId = id,
                sessionId = OcDto.optStr(p, "sessionID"),
                type = OcDto.optStr(p, "type", "permission"),
                patterns = patterns,
                title = OcDto.optStr(p, "title"),
                metadata = p.optJSONObject("metadata")
            )
        }
        val inner = p.optJSONObject("properties") ?: return null
        return permissionFromProps(inner)
    }
}
