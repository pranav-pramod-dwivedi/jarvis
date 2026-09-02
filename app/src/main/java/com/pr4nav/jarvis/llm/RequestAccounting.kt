package com.pr4nav.jarvis.llm

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hard Invariant Request Accounting:
 * ONE USER TURN -> ONE MODEL REQUEST
 *
 * Tracks every model attempt per user turn:
 * parentRequestId, attemptNumber, model, reason, fallback, status, latency.
 * Ensures zero speculative, judge, or parallel requests burn API quota.
 */
object RequestAccounting {

    private const val TAG = "RequestAccounting"

    data class TurnAudit(
        val requestId: String,
        val userQuery: String,
        val timestamp: Long = System.currentTimeMillis(),
        val attempts: MutableList<Attempt> = mutableListOf()
    )

    data class Attempt(
        val attemptNumber: Int,
        val model: String,
        val reason: String,
        val isFallback: Boolean,
        var status: String = "IN_PROGRESS",
        var latencyMs: Long = 0L,
        var exitCode: Int? = null
    )

    private val activeTurns = ConcurrentHashMap<String, TurnAudit>()
    private val completedTurns = ConcurrentHashMap<String, TurnAudit>()

    fun startTurn(query: String, explicitRequestId: String? = null): String {
        val reqId = explicitRequestId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().take(8)
        activeTurns[reqId] = TurnAudit(reqId, query)
        return reqId
    }

    fun recordAttemptStart(requestId: String, model: String, reason: String, isFallback: Boolean): Int {
        val audit = activeTurns[requestId] ?: TurnAudit(requestId, "").also { activeTurns[requestId] = it }
        val attemptNum = audit.attempts.size + 1
        audit.attempts.add(
            Attempt(
                attemptNumber = attemptNum,
                model = model,
                reason = reason,
                isFallback = isFallback
            )
        )
        Log.i(TAG, "request=$requestId attempt=$attemptNum model=$model reason=\"$reason\" fallback=$isFallback status=START")
        return attemptNum
    }

    fun recordAttemptEnd(requestId: String, attemptNumber: Int, status: String, latencyMs: Long) {
        val audit = activeTurns[requestId] ?: completedTurns[requestId]
        val attempt = audit?.attempts?.firstOrNull { it.attemptNumber == attemptNumber }
        if (attempt != null) {
            attempt.status = status
            attempt.latencyMs = latencyMs
        }
        val modelName = attempt?.model ?: "unknown"
        val isFallback = attempt?.isFallback ?: false
        Log.i(TAG, "request=$requestId attempt=$attemptNumber model=$modelName fallback=$isFallback status=$status latency=${latencyMs}ms")
    }

    fun getAudit(requestId: String): TurnAudit? = activeTurns[requestId] ?: completedTurns[requestId]

    fun finishTurn(requestId: String): TurnAudit? {
        val audit = activeTurns.remove(requestId)
        if (audit != null) {
            completedTurns[requestId] = audit
            val totalAttempts = audit.attempts.size
            Log.i(TAG, "request=$requestId turn finished | totalModelRequests=$totalAttempts | finalStatus=${audit.attempts.lastOrNull()?.status ?: "NONE"}")
        }
        return audit
    }

    fun clear() {
        activeTurns.clear()
        completedTurns.clear()
    }
}
