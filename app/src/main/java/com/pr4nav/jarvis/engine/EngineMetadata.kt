package com.pr4nav.jarvis.engine

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class EngineType {
    NEEDLE_REFLEX,
    QWEN_LOCAL,
    AGY_AGENT,
    AUTO_ROUTER
}

data class FallbackChainStep(
    val stepNumber: Int,
    val engine: EngineType,
    val status: String,
    val reason: String
)

data class EngineMetadata(
    val requestedEngine: EngineType,
    val actualEngine: EngineType,
    val provider: String,
    val runtimeBackend: String,
    val modelPath: String,
    val modelFilename: String,
    val modelHashSha256: String,
    val tokenizer: String,
    val runtimeInstanceId: String = UUID.randomUUID().toString(),
    val isModelLoaded: Boolean,
    val isRoutingIntegrityValid: Boolean = (requestedEngine == actualEngine || requestedEngine == EngineType.AUTO_ROUTER)
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("requested_engine", requestedEngine.name)
        put("actual_engine", actualEngine.name)
        put("provider", provider)
        put("runtime_backend", runtimeBackend)
        put("model_path", modelPath)
        put("model_filename", modelFilename)
        put("model_hash_sha256", modelHashSha256)
        put("tokenizer", tokenizer)
        put("runtime_instance_id", runtimeInstanceId)
        put("is_model_loaded", isModelLoaded)
        put("routing_integrity_valid", isRoutingIntegrityValid)
    }

    companion object {
        fun computeFileSha256(file: File?): String {
            if (file == null || !file.exists()) return "FILE_NOT_FOUND"
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead = input.read(buffer)
                    var totalRead = 0L
                    while (bytesRead != -1 && totalRead < 1_048_576L) {
                        digest.update(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        bytesRead = input.read(buffer)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }.take(16) + "..."
            } catch (e: Exception) {
                "HASH_ERROR: ${e.message}"
            }
        }
    }
}

data class EngineInferenceResult(
    val success: Boolean,
    val rawOutput: String,
    val intent: String?,
    val arguments: JSONObject?,
    val confidence: Float,
    val metadata: EngineMetadata,
    val latencyMs: Long,
    val error: String? = null,
    val fallbackChain: List<FallbackChainStep> = emptyList()
)
