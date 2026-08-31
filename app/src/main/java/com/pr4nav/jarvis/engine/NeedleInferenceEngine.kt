package com.pr4nav.jarvis.engine

import android.content.Context
import com.pr4nav.jarvis.needle.NeedleRuntime
import org.json.JSONObject
import java.io.File

/**
 * Isolated Needle 2 Reflex Engine.
 * Performs fast-path deterministic grammar and binary execution.
 * Never silently delegates to Qwen or AGY.
 */
class NeedleInferenceEngine(private val context: Context) {

    fun infer(prompt: String): EngineInferenceResult {
        val t0 = System.currentTimeMillis()
        val envelope = NeedleRuntime.complete(prompt)
        val latency = System.currentTimeMillis() - t0

        val firstCall = envelope.functionCalls.firstOrNull()
        val argsObj = firstCall?.let { JSONObject(it.arguments) }

        val metadata = EngineMetadata(
            requestedEngine = EngineType.NEEDLE_REFLEX,
            actualEngine = EngineType.NEEDLE_REFLEX,
            provider = "embedded_native",
            runtimeBackend = "Needle 2.0 Native Grammar/Daemon",
            modelPath = File(context.filesDir, "needle").absolutePath,
            modelFilename = "needle-2.bin",
            modelHashSha256 = EngineMetadata.computeFileSha256(File(context.filesDir, "needle/needle-2.bin")),
            tokenizer = "GrammarTokenizer-v2",
            isModelLoaded = NeedleRuntime.isModelLoaded
        )

        return EngineInferenceResult(
            success = envelope.success,
            rawOutput = envelope.rawJson.toString(),
            intent = firstCall?.name,
            arguments = argsObj,
            confidence = envelope.confidence.toFloat(),
            metadata = metadata,
            latencyMs = latency
        )
    }
}
