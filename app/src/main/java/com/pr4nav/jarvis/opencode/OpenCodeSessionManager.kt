package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.OpenCodeClient.PromptPart
import com.pr4nav.jarvis.opencode.json.OcEvent
import com.pr4nav.jarvis.opencode.json.OcMessageInfo
import com.pr4nav.jarvis.opencode.json.OcModelRef
import com.pr4nav.jarvis.opencode.json.OcPart
import com.pr4nav.jarvis.opencode.json.OcSessionInfo
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed class QueueDecision {
    object SUBMIT_NOW : QueueDecision()
    object ENQUEUE : QueueDecision()
    object REJECT_FULL : QueueDecision()
}

class OpenCodeSessionManager(
    private val client: OpenCodeClient,
    private val kv: OcKvStore
) {

    enum class PromptOutcome { STARTED, QUEUED }

    sealed class PromptSubmit {
        data class Started(val sessionId: String, val response: JSONObject?) : PromptSubmit()
        data class Queued(val sessionId: String, val position: Int) : PromptSubmit()
        data class Failed(val error: OpenCodeException) : PromptSubmit()
    }

    class SessionEntry(
        val sessionId: String,
        var directory: String,
        @Volatile var title: String?,
        @Volatile var model: OcModelRef?,
        @Volatile var agent: String?,
        @Volatile var archived: Boolean,
        @Volatile var label: String?,
        val createdAtMs: Long,
        @Volatile var lastActivityAtMs: Long,
        @Volatile var busySinceMs: Long = 0,
        @Volatile var lastError: String? = null
    ) {
        val lock = Object()
        @Volatile var busy: Boolean = false
        @Volatile var unread: Int = 0
        internal val queue = ArrayDeque<PendingPrompt>()

        val isBackground: Boolean get() = true
    }

    data class PendingPrompt(
        val parts: List<PromptPart>,
        val agent: String?,
        val model: OcModelRef?
    )

    private val entries = ConcurrentHashMap<String, SessionEntry>()

    @Volatile
    var currentSessionId: String? = null

    private val ioPool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "opencode-sess-io").apply { isDaemon = true }
    }

    var onChanged: (() -> Unit)? = null

    companion object {
        const val TAG = "Sessions"
        const val MAX_QUEUE = 8
        const val BUSY_GRACE_MS = 2_000L

        fun decisionForQueue(busy: Boolean, queueSize: Int, max: Int = MAX_QUEUE): QueueDecision =
            when {
                !busy -> QueueDecision.SUBMIT_NOW
                queueSize >= max -> QueueDecision.REJECT_FULL
                else -> QueueDecision.ENQUEUE
            }
    }

    init {
        OpenCodeSessionStore.loadRegistry(kv).forEach { rec ->
            entries[rec.sessionId] = SessionEntry(
                sessionId = rec.sessionId,
                directory = rec.directory,
                title = rec.title ?: rec.label,
                model = rec.modelId?.let { m ->
                    rec.modelProviderId?.let { p -> OcModelRef(p, m, rec.variant) }
                },
                agent = rec.agent,
                archived = rec.archived,
                label = rec.label,
                createdAtMs = rec.createdAtMs,
                lastActivityAtMs = rec.lastActivityAtMs
            )
        }
        currentSessionId = kv.getString(OpenCodeSessionStore.KEY_CURRENT)
            ?.takeIf { entries.containsKey(it) }
    }

    fun list(includeArchived: Boolean = false): List<SessionEntry> =
        entries.values.filter { includeArchived || !it.archived }
            .sortedByDescending { it.lastActivityAtMs }

    fun get(sessionId: String): SessionEntry? = entries[sessionId]

    fun current(): SessionEntry? = currentSessionId?.let { entries[it] }

    fun createSession(
        directory: String,
        label: String? = null,
        makeCurrent: Boolean = true,
        agent: String? = null,
        model: OcModelRef? = null
    ): OcResult<SessionEntry> = ocTry(TAG) {
        when (val r = client.createSession(directory)) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> {
                val info = r.value
                val entry = registerExisting(info, label, agent, model)
                if (makeCurrent) setCurrent(entry.sessionId)
                persist()
                notifyChanged()
                entry
            }
        }
    }

    fun registerExisting(
        info: OcSessionInfo,
        label: String? = null,
        agent: String? = null,
        model: OcModelRef? = null
    ): SessionEntry {
        val existing = entries[info.id]
        if (existing != null) {
            existing.title = info.title ?: existing.title
            existing.directory = info.directory ?: existing.directory
            if (agent != null) existing.agent = agent
            if (model != null) existing.model = model
            info.updatedAtMs?.let { existing.lastActivityAtMs = it }
            return existing
        }
        val entry = SessionEntry(
            sessionId = info.id,
            directory = info.directory ?: "",
            title = info.title ?: label,
            model = model,
            agent = agent,
            archived = false,
            label = label,
            createdAtMs = info.createdAtMs ?: System.currentTimeMillis(),
            lastActivityAtMs = info.updatedAtMs ?: System.currentTimeMillis()
        )
        entries[info.id] = entry
        return entry
    }

    fun loadSession(sessionId: String, makeCurrent: Boolean = true): OcResult<SessionEntry> = ocTry(TAG) {
        entries[sessionId]?.let { entry ->
            if (makeCurrent) setCurrent(sessionId)
            return@ocTry entry
        }
        when (val r = client.session(sessionId)) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> {
                val entry = registerExisting(r.value)
                if (makeCurrent) setCurrent(sessionId)
                persist()
                notifyChanged()
                entry
            }
        }
    }

    fun resumeLastSession(directory: String?): OcResult<SessionEntry> = ocTry(TAG) {
        val candidates = list().filter { directory == null || it.directory == directory }
        val local = candidates.maxByOrNull { it.lastActivityAtMs }
        if (local != null) {
            setCurrent(local.sessionId)
            return@ocTry local
        }
        when (val r = client.sessions(directory)) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> {
                val newest = r.value
                    .filter { !it.isChild }
                    .maxByOrNull { it.updatedAtMs ?: 0L }
                    ?: throw OpenCodeException(OpenCodeException.Code.NOT_FOUND, "No sessions to resume")
                val entry = registerExisting(newest)
                setCurrent(entry.sessionId)
                persist()
                entry
            }
        }
    }

    fun setCurrent(sessionId: String?) {
        currentSessionId = sessionId
        if (sessionId != null) kv.putString(OpenCodeSessionStore.KEY_CURRENT, sessionId)
        else kv.remove(OpenCodeSessionStore.KEY_CURRENT)
        entries[sessionId]?.let { it.unread = 0 }
        notifyChanged()
    }

    fun refreshFromServer(directory: String? = null): OcResult<Int> = ocTry(TAG) {
        when (val r = client.sessions(directory)) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> {
                var added = 0
                r.value.forEach { info ->
                    if (!entries.containsKey(info.id)) {
                        registerExisting(info)
                        added++
                    } else {
                        entries[info.id]?.let { e ->
                            e.title = info.title ?: e.title
                            info.updatedAtMs?.let { e.lastActivityAtMs = it }
                        }
                    }
                }
                added
            }
        }
    }

    fun backfillMessages(sessionId: String, limit: Int? = null):
        OcResult<List<Pair<OcMessageInfo, List<OcPart>>>> =
        client.messages(sessionId, limit, entries[sessionId]?.directory)

    fun sendPrompt(
        sessionId: String,
        parts: List<PromptPart>,
        agent: String? = null,
        model: OcModelRef? = null
    ): PromptSubmit {
        val entry = entries[sessionId]
            ?: return PromptSubmit.Failed(
                OpenCodeException(OpenCodeException.Code.NOT_FOUND, "Unknown session $sessionId")
            )
        val effectiveAgent = agent ?: entry.agent
        val effectiveModel = model ?: entry.model
        synchronized(entry.lock) {
            when (decisionForQueue(entry.busy, entry.queue.size)) {
                QueueDecision.REJECT_FULL ->
                    return PromptSubmit.Failed(
                        OpenCodeException.busy("Session $sessionId busy, queue full ($MAX_QUEUE)")
                    )
                QueueDecision.ENQUEUE -> {
                    entry.queue.addLast(PendingPrompt(parts, effectiveAgent, effectiveModel))
                    return PromptSubmit.Queued(sessionId, entry.queue.size)
                }
                QueueDecision.SUBMIT_NOW -> entry.busy = true
            }
        }
        entry.busySinceMs = System.currentTimeMillis()
        entry.lastError = null
        val res = submitNow(entry, PendingPrompt(parts, effectiveAgent, effectiveModel))
        if (res is PromptSubmit.Failed) {
            synchronized(entry.lock) {
                entry.busy = false
                entry.lastError = res.error.message
            }
            drainQueued(entry)
        }
        return res
    }

    private fun submitNow(entry: SessionEntry, pending: PendingPrompt): PromptSubmit {
        return when (
            val r = client.promptAsync(
                entry.sessionId,
                pending.parts,
                entry.directory.ifBlank { null },
                pending.agent,
                pending.model
            )
        ) {
            is OcResult.Ok -> PromptSubmit.Started(entry.sessionId, r.value)
            is OcResult.Err -> PromptSubmit.Failed(r.error)
        }
    }

    private fun drainQueued(entry: SessionEntry) {
        while (true) {
            val next = synchronized(entry.lock) {
                if (entry.busy || entry.queue.isEmpty()) return
                entry.busy = true
                entry.queue.removeFirst()
            }
            entry.busySinceMs = System.currentTimeMillis()
            val res = submitNow(entry, next)
            if (res is PromptSubmit.Failed) {
                synchronized(entry.lock) {
                    entry.busy = false
                    entry.lastError = res.error.message
                }
            } else return
        }
    }

    fun abort(sessionId: String): OcResult<Boolean> = ocTry(TAG) {
        val entry = entries[sessionId]
            ?: throw OpenCodeException(OpenCodeException.Code.NOT_FOUND, "Unknown session $sessionId")
        val aborted = when (val r = client.abortSession(sessionId, entry.directory.ifBlank { null })) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> r.value
        }
        synchronized(entry.lock) {
            entry.queue.clear()
            entry.busy = false
        }
        notifyChanged()
        aborted
    }

    fun fork(sessionId: String, messageId: String? = null): OcResult<SessionEntry> = ocTry(TAG) {
        val dir = entries[sessionId]?.directory
        when (val r = client.forkSession(sessionId, messageId, dir)) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> {
                val entry = registerExisting(r.value, label = entries[sessionId]?.label)
                persist()
                notifyChanged()
                entry
            }
        }
    }

    fun rename(sessionId: String, title: String): OcResult<Unit> = ocTry(TAG) {
        val entry = entries[sessionId]
            ?: throw OpenCodeException(OpenCodeException.Code.NOT_FOUND, "Unknown session $sessionId")
        when (val r = client.renameSession(sessionId, title, entry.directory.ifBlank { null })) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> entry.title = title
        }
        persist()
        notifyChanged()
    }

    fun archive(sessionId: String): OcResult<Unit> = ocTry(TAG) {
        val entry = entries[sessionId]
            ?: throw OpenCodeException(OpenCodeException.Code.NOT_FOUND, "Unknown session $sessionId")
        when (val r = client.deleteSession(sessionId, entry.directory.ifBlank { null })) {
            is OcResult.Err ->
                if (r.error.code != OpenCodeException.Code.UNSUPPORTED &&
                    r.error.code != OpenCodeException.Code.BAD_REQUEST
                ) throw r.error
            is OcResult.Ok -> Unit
        }
        entry.archived = true
        if (currentSessionId == sessionId) setCurrent(null)
        persist()
        notifyChanged()
    }

    fun reconcileWithStatusMap(): OcResult<Int> = ocTry(TAG) {
        when (val r = client.statusMap()) {
            is OcResult.Err -> throw r.error
            is OcResult.Ok -> {
                var changed = 0
                val now = System.currentTimeMillis()
                entries.values.forEach { entry ->
                    val server = r.value[entry.sessionId]
                    val serverBusy = server?.isBusy == true
                    synchronized(entry.lock) {
                        if (!serverBusy && entry.busy && now - entry.busySinceMs > BUSY_GRACE_MS) {
                            entry.busy = false
                            changed++
                        }
                    }
                }
                drainAllIdle()
                changed
            }
        }
    }

    fun handleEvent(event: OcEvent) {
        when (event) {
            is OcEvent.SessionIdle -> markIdle(event.sessionId)
            is OcEvent.SessionError -> {
                entries[event.sessionId ?: return]?.let { entry ->
                    entry.lastError = event.errorMessage
                    markIdle(entry.sessionId)
                }
            }
            is OcEvent.SessionCreated -> {
                val info = event.session
                if (!entries.containsKey(info.id)) registerExisting(info)
            }
            is OcEvent.SessionUpdated -> {
                val info = event.session
                entries[info.id]?.let { e ->
                    e.title = info.title ?: e.title
                    info.updatedAtMs?.let { e.lastActivityAtMs = it }
                }
            }
            is OcEvent.MessageUpdated -> {
                val m = event.message
                val sid = m.sessionId ?: return
                entries[sid]?.let { entry ->
                    entry.lastActivityAtMs = System.currentTimeMillis()
                    if (m.role == "assistant" && sid != currentSessionId) {
                        entry.unread++
                    }
                }
            }
            else -> Unit
        }
    }

    private fun markIdle(sessionId: String) {
        val entry = entries[sessionId] ?: return
        synchronized(entry.lock) {
            entry.busy = false
            entry.lastActivityAtMs = System.currentTimeMillis()
        }
        notifyChanged()
        ioPool.execute { drainQueued(entry) }
    }

    private fun drainAllIdle() {
        entries.values.forEach { entry ->
            val shouldDrain = synchronized(entry.lock) { !entry.busy && entry.queue.isNotEmpty() }
            if (shouldDrain) ioPool.execute { drainQueued(entry) }
        }
    }

    fun persist() {
        val records = entries.values.map { e ->
            OpenCodeSessionStore.SessionRecord(
                sessionId = e.sessionId,
                directory = e.directory,
                title = e.title,
                label = e.label,
                modelProviderId = e.model?.providerID,
                modelId = e.model?.modelID,
                variant = e.model?.variant,
                agent = e.agent,
                archived = e.archived,
                createdAtMs = e.createdAtMs,
                updatedAtMs = System.currentTimeMillis(),
                lastActivityAtMs = e.lastActivityAtMs
            )
        }
        OpenCodeSessionStore.saveRegistry(kv, records)
    }

    private fun notifyChanged() {
        try {
            onChanged?.invoke()
        } catch (_: Exception) {
        }
    }
}
