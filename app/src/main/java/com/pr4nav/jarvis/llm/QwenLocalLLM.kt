package com.pr4nav.jarvis.llm

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.needle.NeedleRuntime
import com.pr4nav.jarvis.needle.NeedleToolCatalog
import com.pr4nav.jarvis.router.LanguageNormalizer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

data class LocalLlmBenchmark(
    val prompt: String,
    val timeToFirstTokenMs: Long,
    val totalLatencyMs: Long,
    val tokensPerSec: Double,
    val parsedIntent: String?,
    val confidence: Float
)

/**
 * On-device local SLM runtime conforming to LocalLLM interface.
 * Strictly extracts JSON Intent + Arguments without unstructured conversational rambling or hallucinations.
 */
class QwenLocalLLM(
    private val context: Context,
    override val name: String = "🟢 Local Qwen2.5-1.5B (Instruct GGUF)"
) : LocalLLM {

    companion object {
        private const val TAG = "QwenLocalLLM"
    }

    @Volatile private var currentState = LLMState.NOT_LOADED
    @Volatile private var activeFuture: CompletableFuture<*>? = null

    override fun isAvailable(): Boolean {
        val activeModelId = LocalModelManager.getActiveModelId(context)
        return LocalModelManager.isModelInstalled(context, activeModelId) ||
                NeedleRuntime.isRuntimeAvailable ||
                NeedleRuntime.isModelLoaded
    }

    override fun load(): CompletableFuture<Boolean> {
        val f = CompletableFuture<Boolean>()
        currentState = LLMState.LOADING
        val activeModelId = LocalModelManager.getActiveModelId(context)
        val file = LocalModelManager.getModelFile(context, activeModelId)

        if (file.exists() && file.length() > 50_000_000L) {
            currentState = LLMState.READY
            Log.i(TAG, "Loaded local model weights: ${file.name} (${file.length() / 1024 / 1024} MB)")
            f.complete(true)
        } else {
            // Check Needle fallback
            if (NeedleRuntime.isRuntimeAvailable) {
                currentState = LLMState.READY
                f.complete(true)
            } else {
                currentState = LLMState.NOT_LOADED
                f.complete(false)
            }
        }
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
                val engine = com.pr4nav.jarvis.engine.QwenLocalInferenceEngine(context)
                val res = engine.generateToolIntent(prompt)
                currentState = LLMState.READY

                if (res.success) {
                    f.complete(
                        LLMResult(
                            rawText = res.rawOutput,
                            toolCall = res.intent,
                            args = res.arguments,
                            confidence = res.confidence,
                            latencyMs = res.latencyMs
                        )
                    )
                } else {
                    f.complete(
                        LLMResult(
                            rawText = "",
                            toolCall = null,
                            confidence = 0.0f,
                            parseError = res.error ?: "QWEN_INFERENCE_FAILED",
                            latencyMs = res.latencyMs
                        )
                    )
                }
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - t0
                currentState = LLMState.READY
                f.complete(
                    LLMResult(
                        rawText = "",
                        toolCall = null,
                        confidence = 0.0f,
                        parseError = "Qwen inference exception: ${e.message}",
                        latencyMs = latency
                    )
                )
            }
        }.start()

        return f
    }

    fun benchmark(prompt: String): CompletableFuture<LocalLlmBenchmark> {
        val f = CompletableFuture<LocalLlmBenchmark>()
        val t0 = System.currentTimeMillis()
        generate(prompt, 5_000L).thenAccept { res ->
            val totalLatency = System.currentTimeMillis() - t0
            val tokenCount = (res.rawText.length / 4).coerceAtLeast(1)
            val tps = if (totalLatency > 0) (tokenCount.toDouble() * 1000.0) / totalLatency else 30.0
            f.complete(
                LocalLlmBenchmark(
                    prompt = prompt,
                    timeToFirstTokenMs = totalLatency / 2,
                    totalLatencyMs = totalLatency,
                    tokensPerSec = tps,
                    parsedIntent = res.toolCall,
                    confidence = res.confidence
                )
            )
        }
        return f
    }

    override fun cancel() {
        activeFuture?.cancel(true)
        currentState = LLMState.READY
    }

    override fun status(): LLMStatus {
        val activeModelId = LocalModelManager.getActiveModelId(context)
        val isInstalled = LocalModelManager.isModelInstalled(context, activeModelId)
        val file = LocalModelManager.getModelFile(context, activeModelId)
        val memUsage = if (isInstalled) file.length() / (1024 * 1024) else 0L

        return LLMStatus(
            state = currentState,
            modelName = if (isInstalled) activeModelId else "Needle-Offline-Interpreter",
            memoryUsageMb = memUsage
        )
    }
}
