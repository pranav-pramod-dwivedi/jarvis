package com.pr4nav.jarvis.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.pr4nav.jarvis.llm.KiraClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Kira 3.0 Flash TTS Engine Client (https://kiraai.vn/api/v1/audio/speech)
 *
 * Implements:
 * 1. Default high-definition neural speech engine: "kira-3.0-flash-tts".
 * 2. Voice: user-selected cloud voice (default "achernar"); falls back to "alloy".
 * 3. Network awareness: detects online state; gracefully yields to local Android TTS when offline.
 * 4. Automatic linear PCM (24kHz 16-bit mono) to RIFF WAV wrapping for zero-latency MediaPlayer playback.
 * 5. High-speed disk caching for instant replay of recurring UI/HUD phrases.
 */
object KiraTtsClient {

    private const val TAG = "KiraTtsClient"
    const val KIRA_TTS_ENDPOINT = "https://kiraai.vn/api/v1/audio/speech"
    const val MODEL_KIRA_FLASH_TTS = "kira-3.0-flash-tts"
    /** Default voice. Overridable per user via VoiceAssistantPreferences cloud TTS voice. */
    const val DEFAULT_VOICE = "achernar"
    /** Legacy fallback voice if the selected voice errors. */
    const val FALLBACK_VOICE = "alloy"

    private val executor = Executors.newCachedThreadPool()

    data class TtsAudioResult(
        val audioFile: File,
        val durationMs: Long,
        val rawPcmBytes: ByteArray
    )

    /**
     * Checks if physical network has internet connectivity.
     */
    fun isNetworkAvailable(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val ni = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                ni != null && ni.isConnected
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check network connectivity: ${e.message}")
            false
        }
    }

    /**
     * Returns true if Kira TTS can be queried (online + valid API key).
     */
    fun isAvailable(context: Context?): Boolean {
        if (context == null) return false
        if (!isNetworkAvailable(context)) return false
        val apiKey = KiraClient.getApiKey(context)
        return apiKey.isNotBlank()
    }

    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "kira_tts_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getCacheKey(text: String, voice: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest("$MODEL_KIRA_FLASH_TTS:$voice:$text".toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "${text.hashCode()}_${voice}"
        }
    }

    /**
     * Synthesizes speech synchronously using kira-3.0-flash-tts.
     * Returns TtsAudioResult containing the WAV file and audio duration, or null on failure.
     */
    fun synthesizeSpeech(
        context: Context,
        text: String,
        voice: String = DEFAULT_VOICE
    ): TtsAudioResult? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "Network unavailable; skipping Kira TTS cloud synthesis")
            return null
        }

        val apiKey = KiraClient.getApiKey(context)
        if (apiKey.isBlank()) {
            Log.w(TAG, "Kira API key missing; skipping Kira TTS")
            return null
        }

        val cacheKey = getCacheKey(trimmed, voice)
        val cacheFile = File(getCacheDir(context), "$cacheKey.wav")

        // Return cached audio if available
        if (cacheFile.exists() && cacheFile.length() > 44) {
            val fileSize = cacheFile.length()
            val pcmSize = (fileSize - 44).coerceAtLeast(0)
            val durationMs = (pcmSize * 1000L) / 48000L
            Log.d(TAG, "Kira TTS cache hit for \"${trimmed.take(30)}…\" (${durationMs}ms)")
            return TtsAudioResult(cacheFile, durationMs, ByteArray(0))
        }

        val t0 = System.currentTimeMillis()
        Log.i(TAG, "Requesting Kira TTS for: \"${trimmed.take(60)}\" (voice: $voice, chars: ${trimmed.length})")

        val voicesToTry = if (voice == FALLBACK_VOICE) listOf(FALLBACK_VOICE) else listOf(voice, FALLBACK_VOICE)
        for (currentVoice in voicesToTry) {
            try {
                val payload = JSONObject().apply {
                    put("model", MODEL_KIRA_FLASH_TTS)
                    put("input", trimmed)
                    put("voice", currentVoice)
                }

                val conn = (URL(KIRA_TTS_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 25_000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("User-Agent", "JARVIS-KiraTTS/1.0")
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    Log.w(TAG, "Kira TTS HTTP $code error (voice: $currentVoice): $err")
                    continue
                }

                val rawAudioBytes = conn.inputStream.use { it.readBytes() }
                if (rawAudioBytes.isEmpty()) {
                    Log.w(TAG, "Kira TTS returned empty audio stream (voice: $currentVoice)")
                    continue
                }

                // Kira TTS returns raw 16-bit linear PCM (24,000 Hz, mono).
                // Add standard 44-byte RIFF WAVE header so MediaPlayer can decode it natively.
                val wavBytes = addWavHeader(rawAudioBytes, sampleRate = 24000, channels = 1, bitsPerSample = 16)
                FileOutputStream(cacheFile).use { it.write(wavBytes) }

                val pcmSize = rawAudioBytes.size.toLong()
                val durationMs = (pcmSize * 1000L) / 48000L
                val latency = System.currentTimeMillis() - t0
                Log.i(TAG, "Synthesized via kira-3.0-flash-tts ($currentVoice, ${rawAudioBytes.size} bytes, ${durationMs}ms audio) in ${latency}ms")

                return TtsAudioResult(cacheFile, durationMs, rawAudioBytes)
            } catch (e: Exception) {
                Log.w(TAG, "Kira TTS attempt failed for voice $currentVoice: ${e.message}")
            }
        }

        return null
    }

    /**
     * Asynchronously synthesizes speech using kira-3.0-flash-tts.
     */
    fun synthesizeSpeechAsync(
        context: Context,
        text: String,
        voice: String = DEFAULT_VOICE,
        onSuccess: (TtsAudioResult) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            val result = synthesizeSpeech(context, text, voice)
            if (result != null) {
                onSuccess(result)
            } else {
                onError("Failed to synthesize speech with kira-3.0-flash-tts")
            }
        }
    }

    /**
     * Wraps raw 16-bit PCM audio in standard RIFF WAVE file header.
     */
    fun addWavHeader(
        pcmData: ByteArray,
        sampleRate: Int = 24000,
        channels: Short = 1,
        bitsPerSample: Short = 16
    ): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        val header = ByteArray(44)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(totalDataLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))

        // "fmt " sub-chunk
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16) // Subchunk1Size for PCM
        bb.putShort(1) // AudioFormat 1 = PCM
        bb.putShort(channels)
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign)
        bb.putShort(bitsPerSample)

        // "data" sub-chunk
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(pcmData.size)

        val wav = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wav, 0, 44)
        System.arraycopy(pcmData, 0, wav, 44, pcmData.size)
        return wav
    }
}
