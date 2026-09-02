package com.pr4nav.jarvis.voice

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * High-Performance Model Download & Lifecycle Manager for JARVIS.
 *
 * Downloads AI weights on-demand from official GitHub release assets
 * to eliminate bloated APK sizes and allow independent model updates.
 */
object ModelDownloadManager {

    private const val TAG = "ModelDownloadManager"

    const val MODEL_WAKEWORD = "openwakeword"
    const val MODEL_KOKORO_TTS = "kokoro_tts"

    private const val WAKEWORD_ZIP_URL =
        "https://github.com/pranav-pramod-dwivedi/jarvis/releases/download/v1.0.0-models/openwakeword-models.zip"

    private const val KOKORO_ZIP_URL =
        "https://github.com/pranav-pramod-dwivedi/jarvis/releases/download/v1.0.0-models/kokoro-tts-v1.0.zip"

    interface DownloadListener {
        fun onProgress(modelId: String, progressPercent: Int, bytesRead: Long, totalBytes: Long)
        fun onSuccess(modelId: String)
        fun onError(modelId: String, error: String)
    }

    fun getKokoroDir(context: Context): File {
        val dir = File(context.filesDir, "kokoro")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getWakeWordDir(context: Context): File {
        val dir = File(context.filesDir, "openwakeword")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isWakeWordInstalled(context: Context): Boolean {
        // Check in filesDir or openwakeword subdir or fallback in root filesDir
        val d1 = File(getWakeWordDir(context), "hey_jarvis_v0.1.onnx")
        val d2 = File(context.filesDir, "hey_jarvis_v0.1.onnx")
        val emb1 = File(getWakeWordDir(context), "embedding_model.onnx")
        val emb2 = File(context.filesDir, "embedding_model.onnx")
        val mel1 = File(getWakeWordDir(context), "melspectrogram.onnx")
        val mel2 = File(context.filesDir, "melspectrogram.onnx")

        return (d1.exists() || d2.exists()) &&
               (emb1.exists() || emb2.exists()) &&
               (mel1.exists() || mel2.exists())
    }

    fun isKokoroTtsInstalled(context: Context): Boolean {
        val dir = getKokoroDir(context)
        val onnx = File(dir, "kokoro-v1.0.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        val hasVoice = listOf("bm_george.bin", "af_heart.bin", "voices.bin").any {
            val f = File(dir, it)
            f.exists() && f.length() > 10_000L
        }
        val hasDict = File(dir, "phoneme_dict.json").exists() || File(dir, "dict.txt").exists()

        return onnx.exists() && onnx.length() > 50_000_000L &&
               tokens.exists() && hasVoice && hasDict
    }

    fun isModelInstalled(context: Context, modelId: String): Boolean {
        return when (modelId) {
            MODEL_WAKEWORD -> isWakeWordInstalled(context)
            MODEL_KOKORO_TTS -> isKokoroTtsInstalled(context)
            else -> false
        }
    }

    fun getModelDiskSize(context: Context, modelId: String): Long {
        return when (modelId) {
            MODEL_WAKEWORD -> {
                var sum = 0L
                listOf("hey_jarvis_v0.1.onnx", "embedding_model.onnx", "melspectrogram.onnx").forEach { name ->
                    val f = File(getWakeWordDir(context), name)
                    val f2 = File(context.filesDir, name)
                    if (f.exists()) sum += f.length() else if (f2.exists()) sum += f2.length()
                }
                sum
            }
            MODEL_KOKORO_TTS -> {
                val dir = getKokoroDir(context)
                var sum = 0L
                dir.listFiles()?.forEach { sum += it.length() }
                sum
            }
            else -> 0L
        }
    }

    fun downloadModel(context: Context, modelId: String, listener: DownloadListener) {
        val (urlStr, targetDir) = when (modelId) {
            MODEL_WAKEWORD -> Pair(WAKEWORD_ZIP_URL, getWakeWordDir(context))
            MODEL_KOKORO_TTS -> Pair(KOKORO_ZIP_URL, getKokoroDir(context))
            else -> {
                listener.onError(modelId, "Unknown model ID: $modelId")
                return
            }
        }

        thread(name = "ModelDownloader-$modelId") {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            try {
                Log.i(TAG, "Starting download for $modelId from $urlStr")
                val url = URL(urlStr)
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "JARVIS-Android-Client")
                connection.connect()

                var responseCode = connection.responseCode
                // Handle manual redirect if required
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                    val newUrl = connection.getHeaderField("Location")
                    Log.i(TAG, "Redirected to: $newUrl")
                    connection.disconnect()
                    connection = URL(newUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.connect()
                    responseCode = connection.responseCode
                }

                if (responseCode !in 200..299) {
                    throw Exception("HTTP error code: $responseCode (${connection.responseMessage})")
                }

                val totalLength = connection.contentLength.toLong()
                val tempZipFile = File(context.cacheDir, "$modelId-temp.zip")
                inputStream = BufferedInputStream(connection.inputStream, 32768)
                val outputStream = FileOutputStream(tempZipFile)

                val buffer = ByteArray(32768)
                var bytesReadTotal = 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    bytesReadTotal += read
                    val percent = if (totalLength > 0) ((bytesReadTotal * 100) / totalLength).toInt() else -1
                    listener.onProgress(modelId, percent, bytesReadTotal, totalLength)
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()

                Log.i(TAG, "Download finished ($bytesReadTotal bytes). Extracting zip archive to $targetDir...")
                unzip(tempZipFile, targetDir)

                // Also copy wake word models to filesDir root for immediate backward compatibility
                if (modelId == MODEL_WAKEWORD) {
                    targetDir.listFiles()?.forEach { f ->
                        try {
                            f.copyTo(File(context.filesDir, f.name), overwrite = true)
                        } catch (_: Exception) {}
                    }
                }

                tempZipFile.delete()
                Log.i(TAG, "Successfully extracted and verified $modelId!")
                listener.onSuccess(modelId)

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading $modelId: ${e.message}", e)
                listener.onError(modelId, e.message ?: "Download failed")
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun deleteModel(context: Context, modelId: String): Boolean {
        return try {
            when (modelId) {
                MODEL_WAKEWORD -> {
                    getWakeWordDir(context).deleteRecursively()
                    listOf("hey_jarvis_v0.1.onnx", "embedding_model.onnx", "melspectrogram.onnx").forEach {
                        File(context.filesDir, it).delete()
                    }
                    true
                }
                MODEL_KOKORO_TTS -> {
                    getKokoroDir(context).deleteRecursively()
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed deleting $modelId: ${e.message}")
            false
        }
    }
}
