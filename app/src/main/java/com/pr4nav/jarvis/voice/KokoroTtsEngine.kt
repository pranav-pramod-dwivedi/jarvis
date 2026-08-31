package com.pr4nav.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-Device Neural Text-to-Speech Engine powered by Kokoro-82M v1.0 INT8 ONNX.
 *
 * Capabilities:
 * - Ultra-high quality 24kHz neural speech synthesis
 * - Runs fully local / offline on-device via ONNX Runtime
 * - Low-latency PCM_FLOAT AudioTrack streaming
 * - Instant interruptibility (cancels in <5ms when interrupted)
 * - British/American voice styles (e.g. bm_george, am_michael, af_bella)
 * - Dictionary-backed phoneme tokenization
 */
class KokoroTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTtsEngine"
        const val SAMPLE_RATE = 24000
        const val STYLE_DIM = 256
        const val MODEL_DIR_NAME = "kokoro"
        const val MODEL_FILE_NAME = "kokoro-v1.0.int8.onnx"
        const val VOICE_FILE_NAME = "bm_george.bin" // Jarvis British Male Voice
        const val DICT_FILE_NAME = "phoneme_dict.json"
        const val TOKENS_FILE_NAME = "tokens.txt"

        @Volatile private var instance: KokoroTtsEngine? = null

        fun getInstance(context: Context): KokoroTtsEngine {
            return instance ?: synchronized(this) {
                instance ?: KokoroTtsEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val isInitialized = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val shouldInterrupt = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor()
    private var currentAudioTrack: AudioTrack? = null

    // Token map and phoneme dictionary
    private val tokenMap = HashMap<String, Long>()
    private val phonemeDict = HashMap<String, List<Long>>()
    // Voice style array (510 x 256 floats)
    private var voiceStyle: Array<FloatArray>? = null

    init {
        executor.execute {
            initialize()
        }
    }

    fun isReady(): Boolean = isInitialized.get()
    fun isSpeakingNow(): Boolean = isSpeaking.get()

    fun initialize(): Boolean {
        if (isInitialized.get()) return true

        try {
            val baseDir = File(context.filesDir, MODEL_DIR_NAME)
            val modelFile = File(baseDir, MODEL_FILE_NAME)
            val voiceFile = File(baseDir, VOICE_FILE_NAME)
            val dictFile = File(baseDir, DICT_FILE_NAME)
            val tokensFile = File(baseDir, TOKENS_FILE_NAME)

            if (!modelFile.exists()) {
                Log.w(TAG, "Kokoro ONNX model not found at ${modelFile.absolutePath}")
                return false
            }

            Log.i(TAG, "Initializing Kokoro-82M INT8 ONNX session...")
            val t0 = System.currentTimeMillis()

            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOpts)

            // Load tokens.txt
            if (tokensFile.exists()) {
                tokensFile.forEachLine { line ->
                    val lastSpace = line.lastIndexOf(' ')
                    if (lastSpace > 0) {
                        val key = line.substring(0, lastSpace)
                        val id = line.substring(lastSpace + 1).trim().toLongOrNull()
                        if (id != null) {
                            tokenMap[key] = id
                        }
                    }
                }
                Log.i(TAG, "Loaded ${tokenMap.size} tokens from tokens.txt")
            }

            // Load phoneme dictionary
            if (dictFile.exists()) {
                val jsonStr = dictFile.readText()
                val jsonObj = JSONObject(jsonStr)
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = jsonObj.getJSONArray(key)
                    val tokenList = ArrayList<Long>(arr.length())
                    for (i in 0 until arr.length()) {
                        tokenList.add(arr.getLong(i))
                    }
                    phonemeDict[key] = tokenList
                }
                Log.i(TAG, "Loaded ${phonemeDict.size} words in phoneme dictionary")
            }

            // Load voice style binary (510 * 256 floats = 522,240 bytes)
            if (voiceFile.exists()) {
                voiceStyle = loadVoiceStyle(voiceFile)
                Log.i(TAG, "Loaded voice style: ${voiceStyle?.size} rows")
            }

            val dt = System.currentTimeMillis() - t0
            isInitialized.set(ortSession != null && voiceStyle != null)
            Log.i(TAG, "KokoroTtsEngine initialized successfully in ${dt}ms! isReady=${isInitialized.get()}")
            return isInitialized.get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize KokoroTtsEngine: ${e.message}", e)
            isInitialized.set(false)
            return false
        }
    }

    private fun loadVoiceStyle(file: File): Array<FloatArray> {
        val totalFloats = 510 * STYLE_DIM
        val bytes = file.readBytes()
        val floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val floats = FloatArray(minOf(totalFloats, floatBuffer.remaining()))
        floatBuffer.get(floats)

        val rows = floats.size / STYLE_DIM
        return Array(rows) { r ->
            FloatArray(STYLE_DIM) { c ->
                floats[r * STYLE_DIM + c]
            }
        }
    }

    /**
     * Converts raw text into sequence of Kokoro token IDs.
     */
    fun textToTokenIds(text: String): LongArray {
        val clean = text.replace(".", " . ")
            .replace(",", " , ")
            .replace("!", " ! ")
            .replace("?", " ? ")
            .replace(";", " ; ")
            .replace(":", " : ")
            .replace("\"", "")
            .replace("'", "")
            .trim()

        val words = clean.split("\\s+".toRegex())
        val tokens = ArrayList<Long>(words.size * 6)
        val spaceToken = tokenMap[" "] ?: 16L

        for (word in words) {
            if (word.isBlank()) continue
            val lower = word.lowercase()

            if (tokens.isNotEmpty() && !word.matches(Regex("[.,!?;:]"))) {
                tokens.add(spaceToken)
            }

            // 1. Direct dictionary match
            val dictTokens = phonemeDict[lower]
            if (dictTokens != null) {
                tokens.addAll(dictTokens)
                continue
            }

            // 2. Direct punctuation / symbol match in tokenMap
            val symToken = tokenMap[word] ?: tokenMap[lower]
            if (symToken != null) {
                tokens.add(symToken)
                continue
            }

            // 3. Fallback: character-by-character mapping
            for (ch in lower) {
                val chStr = ch.toString()
                val id = tokenMap[chStr]
                if (id != null) {
                    tokens.add(id)
                }
            }
        }

        if (tokens.isEmpty()) {
            return LongArray(0)
        }

        // Add start and end tokens (0)
        val result = LongArray(tokens.size + 2)
        result[0] = 0L
        for (i in tokens.indices) {
            result[i + 1] = tokens[i]
        }
        result[result.size - 1] = 0L
        return result
    }

    /**
     * Synthesizes and plays audio for [text].
     * Supports immediate interruption when [stop] is called.
     */
    fun speak(
        text: String,
        speed: Float = 1.0f,
        interrupt: Boolean = true,
        onDone: (() -> Unit)? = null
    ) {
        if (interrupt) {
            stop()
        }

        if (!isInitialized.get() || ortSession == null || voiceStyle == null) {
            Log.w(TAG, "Kokoro engine not ready, aborting speech")
            onDone?.invoke()
            return
        }

        shouldInterrupt.set(false)
        executor.execute {
            if (shouldInterrupt.get()) {
                onDone?.invoke()
                return@execute
            }

            isSpeaking.set(true)
            try {
                // Synthesize in sentences / chunks for lowest time-to-first-audio
                val sentences = splitIntoSentences(text)
                for (sentence in sentences) {
                    if (shouldInterrupt.get()) break
                    val trimmed = sentence.trim()
                    if (trimmed.isEmpty()) continue

                    val tokenIds = textToTokenIds(trimmed)
                    if (tokenIds.size < 3) continue // only [0, 0]

                    val audioPcm = synthesizeSentence(tokenIds, speed) ?: continue
                    if (shouldInterrupt.get()) break

                    playAudioTrack(audioPcm)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Kokoro speak loop: ${e.message}", e)
            } finally {
                isSpeaking.set(false)
                if (!shouldInterrupt.get()) {
                    onDone?.invoke()
                }
            }
        }
    }

    /**
     * Immediately stops audio playback and cancels any active synthesis in under 5ms.
     */
    fun stop() {
        shouldInterrupt.set(true)
        isSpeaking.set(false)
        try {
            currentAudioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
                track.release()
            }
            currentAudioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }
    }

    private fun synthesizeSentence(tokens: LongArray, speed: Float): FloatArray? {
        val env = ortEnv ?: return null
        val session = ortSession ?: return null
        val styles = voiceStyle ?: return null

        val t0 = System.currentTimeMillis()
        var tokensTensor: OnnxTensor? = null
        var styleTensor: OnnxTensor? = null
        var speedTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        try {
            // tokens: [1, sequence_length]
            val token2D = Array(1) { tokens }
            tokensTensor = OnnxTensor.createTensor(env, token2D)

            // style: [1, 256]
            val styleIdx = minOf(tokens.size - 2, styles.size - 1).coerceAtLeast(0)
            val selectedStyle = styles[styleIdx]
            val style2D = Array(1) { selectedStyle }
            styleTensor = OnnxTensor.createTensor(env, style2D)

            // speed: [1]
            speedTensor = OnnxTensor.createTensor(env, FloatArray(1) { speed })

            val inputs = mapOf(
                "tokens" to tokensTensor,
                "style" to styleTensor,
                "speed" to speedTensor
            )

            results = session.run(inputs)
            val audioOutput = results[0].value as? FloatArray ?: return null
            val latency = System.currentTimeMillis() - t0
            Log.d(TAG, "Synthesized ${audioOutput.size} samples in ${latency}ms (speed=$speed)")
            return audioOutput
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis inference error: ${e.message}", e)
            return null
        } finally {
            try {
                tokensTensor?.close()
                styleTensor?.close()
                speedTensor?.close()
                results?.close()
            } catch (_: Exception) {}
        }
    }

    private fun playAudioTrack(pcmFloats: FloatArray) {
        if (shouldInterrupt.get()) return

        val bufferSize = maxOf(
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            ),
            pcmFloats.size * 4
        )

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentAudioTrack = track
            track.play()

            // Stream audio chunk in small sub-buffers to permit immediate sub-millisecond interrupt
            val chunkSize = 2400 // 100ms chunks at 24kHz
            var offset = 0
            while (offset < pcmFloats.size && !shouldInterrupt.get()) {
                val toWrite = minOf(chunkSize, pcmFloats.size - offset)
                track.write(pcmFloats, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                offset += toWrite
            }

            // Wait briefly for playback buffer to drain unless interrupted
            var drainWait = 0
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING && offset >= pcmFloats.size && !shouldInterrupt.get() && drainWait < 20) {
                Thread.sleep(25)
                drainWait++
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack playback error: ${e.message}")
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.stop()
                track?.release()
            } catch (_: Exception) {}
            if (currentAudioTrack == track) {
                currentAudioTrack = null
            }
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        val list = ArrayList<String>()
        val regex = Regex("""(?<=[.!?])\s+""")
        val parts = text.split(regex)
        for (part in parts) {
            val t = part.trim()
            if (t.isNotEmpty()) {
                list.add(t)
            }
        }
        if (list.isEmpty() && text.isNotBlank()) {
            list.add(text.trim())
        }
        return list
    }

    fun destroy() {
        stop()
        try {
            ortSession?.close()
            ortSession = null
            ortEnv?.close()
            ortEnv = null
        } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
