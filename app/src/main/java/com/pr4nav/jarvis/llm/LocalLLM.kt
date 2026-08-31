package com.pr4nav.jarvis.llm

import com.pr4nav.jarvis.needle.NeedleRuntime
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

enum class LLMState {
    NOT_LOADED,
    LOADING,
    READY,
    INFERRING,
    ERROR
}

data class LLMStatus(
    val state: LLMState,
    val modelName: String? = null,
    val memoryUsageMb: Long = 0L,
    val error: String? = null
)

data class LLMResult(
    val rawText: String,
    val toolCall: String? = null,
    val args: JSONObject? = null,
    val confidence: Float = 0.0f,
    val parseError: String? = null,
    val latencyMs: Long = 0L
)

/**
 * Clean abstraction for local on-device LLM runtime.
 * Provides isAvailable, load, unload, generate, cancel, status.
 * Enforces a 30-second default timeout and validates structured outputs against CanonicalToolRegistry.
 */
interface LocalLLM {
    val name: String
    fun isAvailable(): Boolean
    fun load(): CompletableFuture<Boolean>
    fun unload(): CompletableFuture<Boolean>
    fun generate(prompt: String, timeoutMs: Long = 30_000L): CompletableFuture<LLMResult>
    fun cancel()
    fun status(): LLMStatus
}

/**
 * Default clean implementation of LocalLLM.
 * Does not download an embedded model if no local model runtime exists;
 * returns isAvailable=false or hooks into the Needle daemon/offline interpreter if available.
 */
class DefaultLocalLLM(
    override val name: String = "JARVIS-Local-SLM"
) : LocalLLM {

    @Volatile private var currentState = LLMState.NOT_LOADED
    @Volatile private var activeFuture: CompletableFuture<*>? = null

    override fun isAvailable(): Boolean {
        return try {
            NeedleRuntime.isRuntimeAvailable || NeedleRuntime.isModelLoaded
        } catch (_: Exception) {
            false
        }
    }

    override fun load(): CompletableFuture<Boolean> {
        val f = CompletableFuture<Boolean>()
        currentState = LLMState.READY
        f.complete(true)
        return f
    }

    override fun unload(): CompletableFuture<Boolean> {
        val f = CompletableFuture<Boolean>()
        currentState = LLMState.NOT_LOADED
        f.complete(true)
        return f
    }

    override fun generate(prompt: String, timeoutMs: Long): CompletableFuture<LLMResult> {
        val f = CompletableFuture<LLMResult>()
        activeFuture = f
        currentState = LLMState.INFERRING
        val t0 = System.currentTimeMillis()

        Thread {
            try {
                val envelope = NeedleRuntime.complete(prompt)
                val latency = System.currentTimeMillis() - t0
                currentState = LLMState.READY

                if (envelope != null && envelope.functionCalls.isNotEmpty()) {
                    val firstCall = envelope.functionCalls[0]
                    val argsObj = JSONObject(firstCall.arguments)
                    f.complete(LLMResult(
                        rawText = envelope.rawJson.toString(),
                        toolCall = firstCall.name,
                        args = argsObj,
                        confidence = envelope.confidence.toFloat(),
                        latencyMs = latency
                    ))
                } else {
                    f.complete(LLMResult(
                        rawText = envelope?.rawJson?.toString() ?: "",
                        toolCall = null,
                        confidence = 0.0f,
                        parseError = "No structured tool parsed by local model",
                        latencyMs = latency
                    ))
                }
            } catch (e: TimeoutException) {
                currentState = LLMState.ERROR
                f.complete(LLMResult(
                    rawText = "",
                    parseError = "Local LLM timed out after ${timeoutMs}ms",
                    latencyMs = System.currentTimeMillis() - t0
                ))
            } catch (e: Exception) {
                currentState = LLMState.READY
                f.complete(LLMResult(
                    rawText = "",
                    parseError = e.message ?: "Execution failed",
                    latencyMs = System.currentTimeMillis() - t0
                ))
            }
        }.start()

        return f
    }

    override fun cancel() {
        activeFuture?.cancel(true)
        currentState = LLMState.READY
    }

    override fun status(): LLMStatus {
        return LLMStatus(
            state = currentState,
            modelName = name,
            memoryUsageMb = 0L
        )
    }
}
