package com.pr4nav.jarvis.core

import android.content.Context
import com.pr4nav.jarvis.context.ContextManager
import com.pr4nav.jarvis.environment.JarvisEnvironment
import com.pr4nav.jarvis.router.ActivityEvent
import com.pr4nav.jarvis.router.ActivityState
import com.pr4nav.jarvis.router.JarvisRouter
import com.pr4nav.jarvis.router.RouterDiagnostics
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolCapability
import com.pr4nav.jarvis.tools.ToolCapabilityRegistry
import com.pr4nav.jarvis.tools.ToolResult
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AssistantMessageRequest(
    val message: String,
    val conversationId: String = "default",
    val streamTokens: Boolean = true,
    val requestedModel: String? = null
)

enum class AssistantEventStatus {
    REQUEST_STARTED,
    UNDERSTANDING,
    ROUTING,
    THINKING,
    READING,
    SEARCHING,
    PLANNING,
    EXECUTING,
    TOOL_RESULT,
    GENERATING,
    SPEAKING,
    DONE,
    ERROR,
    CANCELLED
}

data class AssistantEvent(
    val requestId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: AssistantEventStatus,
    val message: String,
    val metadata: JSONObject? = null
)

data class AssistantResponse(
    val requestId: String,
    val conversationId: String,
    val responseText: String,
    val speechText: String,
    val routeSelected: String,
    val confidence: Float,
    val routeReason: String,
    val modelEngine: String,
    val toolResult: ToolResult? = null,
    val latencyMs: Long,
    val events: List<AssistantEvent>
)

/**
 * AssistantCore — The Central Unified Assistant Facade.
 *
 * Provides a stable, decoupled contract for UI, Voice, and background tasks.
 * Implements canonical request lifecycle:
 * INPUT -> NORMALIZE -> CONTEXT -> CLASSIFY -> ROUTE -> EXECUTE -> VERIFY -> RESPOND -> DONE
 */
object AssistantCore {

    private val activeRequests = ConcurrentHashMap<String, Boolean>()

    private var voiceEngine: com.pr4nav.jarvis.voice.JarvisVoiceEngine? = null

    fun init(context: Context) {
        JarvisWorkspace.initWorkspace(context)
        CanonicalToolRegistry.init(context)
        try {
            voiceEngine = com.pr4nav.jarvis.voice.JarvisVoiceEngine(context.applicationContext)
        } catch (_: Exception) {}
    }

    /**
     * Submits a user message through the canonical request lifecycle.
     * Returns a unique request ID immediately for tracking and cancellation.
     */
    fun submitMessage(
        context: Context,
        request: AssistantMessageRequest,
        onEvent: (AssistantEvent) -> Unit,
        onTokenChunk: ((String) -> Unit)? = null,
        onResult: (AssistantResponse) -> Unit
    ): String {
        val requestId = UUID.randomUUID().toString()
        val t0 = System.currentTimeMillis()
        activeRequests[requestId] = true

        val emittedEvents = mutableListOf<AssistantEvent>()

        fun emit(status: AssistantEventStatus, msg: String, meta: JSONObject? = null) {
            val ev = AssistantEvent(
                requestId = requestId,
                status = status,
                message = msg,
                metadata = meta
            )
            emittedEvents.add(ev)
            onEvent(ev)
        }

        emit(AssistantEventStatus.REQUEST_STARTED, "Processing request: ${request.message.take(60)}")

        // Check if cancelled before routing
        if (activeRequests[requestId] != true) {
            emit(AssistantEventStatus.CANCELLED, "Request cancelled by user")
            return requestId
        }

        // Delegate execution to central JarvisRouter
        JarvisRouter.route(
            context = context,
            input = request.message,
            onActivity = { actEvent ->
                if (activeRequests[requestId] == true) {
                    val status = mapActivityToEventStatus(actEvent.state)
                    emit(status, actEvent.detail)
                }
            },
            onChunk = { chunk ->
                if (activeRequests[requestId] == true && request.streamTokens) {
                    emit(AssistantEventStatus.GENERATING, "Streaming response…")
                    onTokenChunk?.invoke(chunk)
                }
            },
            onResult = { routeResult ->
                val isCancelled = activeRequests[requestId] != true
                activeRequests.remove(requestId)

                if (isCancelled) {
                    emit(AssistantEventStatus.CANCELLED, "Request execution cancelled")
                    return@route
                }

                emit(AssistantEventStatus.DONE, "Response ready")

                val finalResp = AssistantResponse(
                    requestId = requestId,
                    conversationId = request.conversationId,
                    responseText = routeResult.responseText,
                    speechText = routeResult.speechText,
                    routeSelected = routeResult.routeSelected,
                    confidence = 0.95f,
                    routeReason = routeResult.thinkingTrace.lines().firstOrNull { it.contains("Reason:") || it.contains("Router:") }?.trim() ?: "Route determined by auto-classifier",
                    modelEngine = routeResult.modelEngine,
                    toolResult = routeResult.toolResult,
                    latencyMs = System.currentTimeMillis() - t0,
                    events = emittedEvents
                )
                onResult(finalResp)
            }
        )

        return requestId
    }

    /**
     * Cancels an in-flight assistant request.
     */
    fun cancelRequest(requestId: String) {
        activeRequests[requestId] = false
        activeRequests.remove(requestId)
    }

    /**
     * Stops TTS speech output immediately without interrupting background tasks.
     */
    fun stopSpeaking() {
        voiceEngine?.stopSpeaking()
    }

    fun getCapabilities(): List<ToolCapability> = ToolCapabilityRegistry.getAll()

    fun getEnvironment(context: Context? = null) = JarvisEnvironment.getSnapshot(context)

    fun getRecentTraces(limit: Int = 20) = RouterDiagnostics.getRecentTraces(limit)

    private fun mapActivityToEventStatus(state: ActivityState): AssistantEventStatus = when (state) {
        ActivityState.LISTENING -> AssistantEventStatus.REQUEST_STARTED
        ActivityState.UNDERSTANDING -> AssistantEventStatus.UNDERSTANDING
        ActivityState.THINKING -> AssistantEventStatus.THINKING
        ActivityState.READING -> AssistantEventStatus.READING
        ActivityState.SEARCHING -> AssistantEventStatus.SEARCHING
        ActivityState.CHECKING -> AssistantEventStatus.ROUTING
        ActivityState.PLANNING -> AssistantEventStatus.PLANNING
        ActivityState.EXECUTING -> AssistantEventStatus.EXECUTING
        ActivityState.WAITING_FOR_RESULT -> AssistantEventStatus.GENERATING
        ActivityState.DONE -> AssistantEventStatus.DONE
    }
}
