package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcEvent
import com.pr4nav.jarvis.opencode.json.OcPart
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class OcToolCall(
    val sessionId: String,
    val callId: String,
    val tool: String,
    var status: Status = Status.PENDING,
    var input: org.json.JSONObject? = null,
    var title: String? = null,
    var output: org.json.JSONObject? = null,
    var startedAtMs: Long = 0,
    var completedAtMs: Long = 0,
    var error: String? = null
) {
    enum class Status { PENDING, RUNNING, COMPLETED, ERROR }

    val filePath: String?
        get() = input?.let {
            listOf("filePath", "path", "file").firstNotNullOfOrNull { k ->
                if (it.has(k) && !it.isNull(k)) it.optString(k) else null
            }
        }
}

class OpenCodeToolTracker {

    interface Listener {
        fun onToolCallsChanged(sessionId: String)
    }

    private val byCallId = ConcurrentHashMap<String, OcToolCall>()
    private val sessionOrder = ConcurrentHashMap<String, MutableList<String>>()

    data class ChangedFiles(val files: MutableMap<String, Pair<Long, Long>> = LinkedHashMap()) {
        fun record(file: String, additions: Long, deletions: Long) {
            files[file] = (files[file]?.first ?: additions) to (files[file]?.second ?: deletions)
        }

        fun summary(): String =
            if (files.isEmpty()) "" else files.entries.joinToString(", ") {
                "${it.key} (+${it.value.first}/-${it.value.second})"
            }
    }

    val changedFiles = ChangedFiles()

    val listeners = CopyOnWriteArrayList<Listener>()

    fun clear() {
        byCallId.clear()
        sessionOrder.clear()
    }

    fun callsForSession(sessionId: String): List<OcToolCall> {
        val ids = sessionOrder[sessionId] ?: return emptyList()
        return ids.mapNotNull { byCallId[it] }
    }

    fun allCalls(): List<OcToolCall> = byCallId.values.sortedBy { it.startedAtMs }

    fun ingestPart(part: OcPart) {
        if (part.type != "tool" || part.callId == null || part.toolName == null) return
        val sid = part.sessionId ?: return
        val call = byCallId.computeIfAbsent(part.callId!!) { id ->
            OcToolCall(sid, id, part.toolName!!)
                .also { call ->
                    sessionOrder.computeIfAbsent(sid) { Collections.synchronizedList(ArrayList()) }
                        .add(id)
                }
        }
        part.toolInput?.let { call.input = it }
        part.toolTitle?.let { call.title = it }
        part.toolMetadata?.let { meta ->
            call.output = meta
            extractDiffFromMetadata(meta)?.let { changedFiles.record(it.first, it.second, it.third) }
        }
        when (part.toolStatus) {
            "pending" -> call.status = OcToolCall.Status.PENDING
            "running" -> {
                if (call.status == OcToolCall.Status.PENDING) call.startedAtMs = System.currentTimeMillis()
                call.status = OcToolCall.Status.RUNNING
            }
            "completed" -> {
                call.status = OcToolCall.Status.COMPLETED
                if (call.completedAtMs == 0L) call.completedAtMs = System.currentTimeMillis()
                if (call.startedAtMs == 0L) call.startedAtMs = call.completedAtMs
            }
            "error" -> {
                call.status = OcToolCall.Status.ERROR
                call.error = part.toolMetadata?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: part.toolTitle
            }
        }
        listeners.forEach { l ->
            try {
                l.onToolCallsChanged(sid)
            } catch (_: Exception) {
            }
        }
    }

    fun ingestDiffEvent(event: OcEvent.SessionDiff) {
        event.files.forEach { f -> changedFiles.record(f.file, f.additions, f.deletions) }
    }

    fun humanStatus(call: OcToolCall): String = when (call.status) {
        OcToolCall.Status.PENDING -> "Queued ${call.tool}"
        OcToolCall.Status.RUNNING -> when (call.tool) {
            "bash" -> "Running command"
            "read" -> "Reading ${call.filePath ?: ""}"
            "write", "edit" -> "Editing ${call.filePath ?: ""}"
            "grep", "glob" -> "Searching"
            "task" -> "Delegating to subagent"
            else -> "Running ${call.tool}"
        }
        OcToolCall.Status.COMPLETED -> "Done: ${call.title ?: call.tool}"
        OcToolCall.Status.ERROR -> "Failed: ${call.error ?: call.tool}"
    }.trim().replace(Regex("\\s+"), " ")

    private fun extractDiffFromMetadata(meta: org.json.JSONObject): Triple<String, Long, Long>? {
        val fd = meta.optJSONObject("filediff") ?: return null
        val file = fd.optString("file").takeIf { it.isNotBlank() } ?: return null
        val diffText = fd.optString("diff")
        var add = 0L
        var del = 0L
        diffText.lines().forEach { line ->
            when {
                line.startsWith("+") && !line.startsWith("+++") -> add++
                line.startsWith("-") && !line.startsWith("---") -> del++
            }
        }
        return Triple(file, add, del)
    }
}
