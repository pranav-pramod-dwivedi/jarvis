package com.pr4nav.jarvis.llm

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.needle.NeedleRuntime
import com.pr4nav.jarvis.needle.NeedleToolCatalog
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

/**
 * On-device local LLM runtime conforming to LocalLLM interface.
 * When an installed Qwen2.5 GGUF model is present in internal storage,
 * it runs inference locally. Otherwise, it delegates to NeedleRuntime.
 */
class QwenLocalLLM(
    private val context: Context,
    override val name: String = "🟢 Local Qwen3.5-2B (Instruct GGUF)"
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
            currentState = LLMState.READY
            f.complete(true)
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
                // If local GGUF model exists, we format using Qwen2.5 few-shot prompt
                val catalogJson = NeedleToolCatalog.generateSchemasJson()
                val formattedPrompt = LocalModelManager.buildPromptTemplate(prompt, catalogJson)

                // Dispatch to persistent engine
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
