package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcEvent
import com.pr4nav.jarvis.opencode.json.OcPermissionRequest
import com.pr4nav.jarvis.opencode.json.OcQuestionRequest
import java.util.concurrent.ConcurrentHashMap

class OpenCodePermissionManager(
    private val client: OpenCodeClient
) {

    private val pendingPermissions = ConcurrentHashMap<String, OcPermissionRequest>()
    private val pendingQuestions = ConcurrentHashMap<String, OcQuestionRequest>()

    var onPendingChanged: (() -> Unit)? = null
    var onNotificationNeeded: ((title: String, body: String) -> Unit)? = null

    fun permissionsSnapshot(): List<OcPermissionRequest> =
        pendingPermissions.values.sortedBy { it.requestId }

    fun questionsSnapshot(): List<OcQuestionRequest> =
        pendingQuestions.values.sortedBy { it.requestId }

    fun pendingForSession(sessionId: String): List<Any> {
        val perms = pendingPermissions.values.filter { it.sessionId == sessionId }
        val qs = pendingQuestions.values.filter { it.sessionId == sessionId }
        return perms + qs
    }

    fun refreshFromServer(directory: String?): OcResult<Unit> = ocTry(TAG) {
        when (val r = client.pendingPermissions(directory)) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> {
                val seen = HashSet<String>()
                r.value.forEach { req ->
                    seen.add(req.requestId)
                    pendingPermissions[req.requestId] = req
                }
                pendingPermissions.keys.removeAll { it !in seen }
            }
        }
        notifyChanged()
    }

    fun respondToPermission(
        requestId: String,
        decision: OpenCodeClient.PermissionDecision,
        directory: String?
    ): OcResult<Unit> = ocTry(TAG) {
        when (val r = client.replyToPermission(requestId, decision, directory)) {
            is OcResult.Err ->
                if (!(r.error.message?.contains("not found", ignoreCase = true) == true &&
                    decision == OpenCodeClient.PermissionDecision.REJECT)
                ) throw r.error
            is OcResult.Ok -> Unit
        }
        pendingPermissions.remove(requestId)
        notifyChanged()
    }

    fun respondToQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?
    ): OcResult<Unit> = ocTry(TAG) {
        when (val r = client.replyToQuestion(requestId, answers, directory)) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> Unit
        }
        pendingQuestions.remove(requestId)
        notifyChanged()
    }

    fun rejectQuestion(requestId: String, directory: String?): OcResult<Unit> = ocTry(TAG) {
        when (val r = client.rejectQuestion(requestId, directory)) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> Unit
        }
        pendingQuestions.remove(requestId)
        notifyChanged()
    }

    fun handleEvent(event: OcEvent) {
        when (event) {
            is OcEvent.PermissionAsked -> {
                pendingPermissions[event.request.requestId] = event.request
                onNotificationNeeded?.invoke(
                    "OpenCode needs permission",
                    "${event.request.type ?: "action"} in ${event.request.sessionId?.take(12) ?: "session"}"
                )
                notifyChanged()
            }
            is OcEvent.PermissionReplied -> {
                pendingPermissions.remove(event.requestId)
                notifyChanged()
            }
            is OcEvent.QuestionAsked -> {
                pendingQuestions[event.request.requestId] = event.request
                onNotificationNeeded?.invoke(
                    "OpenCode asks a question",
                    event.request.questions.firstOrNull()?.question?.take(80) ?: ""
                )
                notifyChanged()
            }
            is OcEvent.QuestionReplied, is OcEvent.QuestionRejected -> {
                val rid = when (event) {
                    is OcEvent.QuestionReplied -> event.requestId
                    is OcEvent.QuestionRejected -> event.requestId
                    else -> null
                }
                if (rid != null) {
                    pendingQuestions.remove(rid)
                    notifyChanged()
                }
            }
            else -> Unit
        }
    }

    private fun notifyChanged() {
        try {
            onPendingChanged?.invoke()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val TAG = "Perms"

        fun describe(request: OcPermissionRequest): String {
            val what = request.title ?: request.type ?: "operation"
            val pattern = request.patterns.firstOrNull()
            return if (pattern != null) "$what · $pattern" else what
        }
    }
}
