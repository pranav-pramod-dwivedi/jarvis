package com.pr4nav.jarvis.llm

import android.content.Context
import android.os.StatFs
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Model specifications for supported local on-device SLMs.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val parameterSize: String,
    val quantFormat: String,
    val downloadUrl: String,
    val estimatedSizeBytes: Long,
    val minRamBytes: Long,
    val recommendedTps: Double
)

/**
 * Manages downloading, storing, and validating on-device local models.
 * Strictly downloads to JARVIS's internal app files directory (not Termux).
 */
object LocalModelManager {

    private const val TAG = "LocalModelManager"
    private const val PREFS_NAME = "jarvis_local_ai_prefs"
    private const val KEY_ACTIVE_MODEL = "active_local_model_id"

    // Supported on-device SLMs (accurately verified against GGUF metadata)
    val AVAILABLE_MODELS = listOf(
        ModelSpec(
            id = "qwen2.5-1.5b-instruct-q4",
            displayName = "🟢 Local Qwen2.5-1.5B (1.5B Instruct - High Reasoning)",
            parameterSize = "1.8 Billion",
            quantFormat = "GGUF Q4_K_M",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            estimatedSizeBytes = 1_117_320_736L, // ~1.04 GB
            minRamBytes = 2_000_000_000L,      // 2.0 GB free RAM
            recommendedTps = 28.0
        ),
        ModelSpec(
            id = "qwen2.5-3b-instruct-q4",
            displayName = "🟢 Local Qwen2.5-3B (3.0B Instruct - Deep Reasoning)",
            parameterSize = "3.09 Billion",
            quantFormat = "GGUF Q4_K_M",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            estimatedSizeBytes = 1_930_000_000L, // ~1.93 GB
            minRamBytes = 3_500_000_000L,
            recommendedTps = 18.0
        ),
        ModelSpec(
            id = "qwen2.5-coder-1.5b-instruct-q4",
            displayName = "🟢 Local Qwen2.5-Coder (1.5B Coder - Autonomous Coding)",
            parameterSize = "1.8 Billion",
            quantFormat = "GGUF Q4_K_M",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            estimatedSizeBytes = 1_117_320_736L, // ~1.04 GB
            minRamBytes = 2_000_000_000L,      // 2.0 GB free RAM
            recommendedTps = 26.5
        )
    )

    private val isDownloading = AtomicBoolean(false)

    data class ModelIntegrityStatus(
        val exists: Boolean,
        val isReadable: Boolean,
        val sizeBytes: Long,
        val hasValidGgufHeader: Boolean,
        val sha256: String,
        val isReady: Boolean,
        val statusText: String
    )

    fun getModelsDir(context: Context?): File {
        val base = try { context?.filesDir } catch (_: Exception) { null }
            ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val dir = File(base, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(context: Context?, modelId: String): File {
        val spec = AVAILABLE_MODELS.firstOrNull { it.id == modelId } ?: AVAILABLE_MODELS[0]
        val fileName = "${spec.id}.gguf"
        val localF = File(getModelsDir(context), fileName)
        if (localF.exists() && localF.length() > 50_000_000L) return localF
        val devicePath = File("/data/user/0/com.pr4nav.jarvis/files/models/$fileName")
        if (devicePath.exists()) return devicePath
        return localF
    }

    fun deleteAllLocalModels(context: Context?): Int {
        var count = 0
        try {
            val dir = getModelsDir(context)
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".gguf") || file.name.endsWith(".bin") || file.name.endsWith(".onnx.model")) {
                    if (file.delete()) count++
                }
            }
            val devDir = File("/data/user/0/com.pr4nav.jarvis/files/models")
            if (devDir.exists()) {
                devDir.listFiles()?.forEach { file ->
                    if (file.delete()) count++
                }
            }
        } catch (_: Exception) {}
        return count
    }

    fun isModelInstalled(context: Context?, modelId: String): Boolean {
        val f = getModelFile(context, modelId)
        if (f.exists() && f.length() > 50_000_000L) return true
        val devicePath = File("/data/user/0/com.pr4nav.jarvis/files/models/${modelId}.gguf")
        if (devicePath.exists() && devicePath.length() > 50_000_000L) return true
        return false
    }

    fun getActiveModelId(context: Context?): String {
        return try {
            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs?.getString(KEY_ACTIVE_MODEL, AVAILABLE_MODELS[0].id) ?: AVAILABLE_MODELS[0].id
        } catch (_: Exception) {
            AVAILABLE_MODELS[0].id
        }
    }

    fun checkFileIntegrity(context: Context?, modelId: String): ModelIntegrityStatus {
        val f = getModelFile(context, modelId)
        if (!f.exists()) {
            return ModelIntegrityStatus(
                exists = false,
                isReadable = false,
                sizeBytes = 0L,
                hasValidGgufHeader = false,
                sha256 = "FILE_NOT_FOUND",
                isReady = false,
                statusText = "NOT INSTALLED (File not found)"
            )
        }

        val size = f.length()
        val readable = f.canRead()
        if (size < 50_000_000L || !readable) {
            return ModelIntegrityStatus(
                exists = true,
                isReadable = readable,
                sizeBytes = size,
                hasValidGgufHeader = false,
                sha256 = "INCOMPLETE_FILE",
                isReady = false,
                statusText = "CORRUPT OR INCOMPLETE (${size / 1024 / 1024} MB)"
            )
        }

        val parsed = GgufMetadataParser.parse(f)
        val identityCheck = parsed.verifyIdentity(modelId)

        return ModelIntegrityStatus(
            exists = true,
            isReadable = readable,
            sizeBytes = size,
            hasValidGgufHeader = parsed.isValidGguf,
            sha256 = com.pr4nav.jarvis.engine.EngineMetadata.computeFileSha256(f),
            isReady = parsed.isValidGguf && identityCheck.isIdentityPass,
            statusText = if (parsed.isValidGguf && identityCheck.isIdentityPass) {
                "VERIFIED & READY (${parsed.modelName} · ${parsed.quantization})"
            } else if (parsed.isValidGguf) {
                "IDENTITY MISMATCH: ${identityCheck.statusText}"
            } else {
                "INVALID GGUF HEADER"
            }
        )
    }

    fun setActiveModelId(context: Context, modelId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_MODEL, modelId).apply()
    }

    fun getAvailableStorageBytes(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Download manager for downloading Qwen models directly to private app storage.
     */
    fun startDownload(
        context: Context,
        modelId: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
        onComplete: (success: Boolean, error: String?) -> Unit
    ) {
        val spec = AVAILABLE_MODELS.firstOrNull { it.id == modelId }
            ?: return onComplete(false, "Unknown model ID: $modelId")

        if (isDownloading.get()) {
            return onComplete(false, "Another model download is currently in progress")
        }

        val avail = getAvailableStorageBytes(context)
        if (avail < spec.estimatedSizeBytes + 100_000_000L) {
            return onComplete(false, "Insufficient internal storage (${avail / 1024 / 1024} MB free, need ${(spec.estimatedSizeBytes / 1024 / 1024) + 100} MB)")
        }

        isDownloading.set(true)
        thread(name = "ModelDownload-$modelId") {
            var conn: HttpURLConnection? = null
            try {
                val targetFile = getModelFile(context, modelId)
                val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
                if (partFile.exists()) partFile.delete()

                val url = URL(spec.downloadUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "JARVIS-Android-SLM-Downloader/1.0")
                }

                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else spec.estimatedSizeBytes
                var downloaded = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var lastReportTime = 0L

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime > 250 || downloaded == total) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                onProgress(pct, downloaded, total)
                                lastReportTime = now
                            }
                        }
                    }
                }

                if (partFile.renameTo(targetFile)) {
                    setActiveModelId(context, modelId)
                    onComplete(true, null)
                } else {
                    onComplete(false, "Failed to rename completed model file")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $modelId: ${e.message}", e)
                onComplete(false, e.message ?: "Network error downloading model")
            } finally {
                isDownloading.set(false)
                conn?.disconnect()
            }
        }
    }

    /**
     * Builds structured few-shot system prompt format for Qwen2.5 function calling.
     */
    fun buildPromptTemplate(userPrompt: String, toolsCatalogJson: String): String {
        return """<|im_start|>system
You are JARVIS, an autonomous on-device Android assistant.
Your goal is to parse user intents into structured tool calls.
Do not explain how to do it. DO the action by returning ONLY a valid JSON tool call.

Available Canonical Tools:
$toolsCatalogJson

Rules:
1. Always output valid JSON conforming to {"name": "<tool_name>", "arguments": {...}}.
2. If the user asks for multiple things or needs conversational reasoning, output {"name": "escalate", "arguments": {"reason": "<why>"}}.
3. Use exact parameter names matching the schema.
<|im_end|>
<|im_start|>user
$userPrompt
<|im_end|>
<|im_start|>assistant
""".trimIndent()
    }
}
