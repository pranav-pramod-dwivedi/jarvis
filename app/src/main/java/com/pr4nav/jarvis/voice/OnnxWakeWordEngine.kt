package com.pr4nav.jarvis.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Production On-Device Neural Wake Word Engine powered by ONNX Runtime.
 *
 * Uses openWakeWord's streaming architecture:
 * 1. melspectrogram.onnx (audio PCM -> 32-bin log-mel spectrogram)
 * 2. embedding_model.onnx (76x32x1 mel buffer -> 96-dim speech embedding)
 * 3. hey_jarvis_v0.1.onnx (16x96 embedding window -> detection probability)
 *
 * Runs completely offline, low-power, continuous 16kHz mono, zero SpeechRecognizer loops.
 */
class OnnxWakeWordEngine : WakeWordEngine {

    companion object {
        private const val TAG = "OnnxWakeWordEngine"
        private const val MEL_CHUNK_SAMPLES = 1280 // 80ms at 16kHz
        private const val HISTORY_SAMPLES = 480    // 160 * 3 context samples
        private const val TOTAL_WINDOW_SAMPLES = MEL_CHUNK_SAMPLES + HISTORY_SAMPLES // 1760 samples
        private const val MEL_FRAMES_REQUIRED = 76
        private const val EMBEDDING_FRAMES_REQUIRED = 16
        private const val EMBEDDING_DIM = 96
        private const val MEL_BINS = 32
        const val DEBUG_WAKE = true
    }

    override val name: String = "openWakeWord (ONNX Engine)"
    @Volatile override var isInstalled: Boolean = false
        private set

    private var env: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var jarvisSession: OrtSession? = null

    // Audio accumulator for raw PCM samples (as Float, range ~ -32768.0 to 32767.0)
    private val rawAudioBuffer = ArrayList<Float>(5120)
    // Mel frames accumulator: rolling buffer of 76 frames, each having 32 bins
    private val melBuffer = ArrayList<FloatArray>(100)
    // Embeddings accumulator: rolling buffer of 16 embeddings, each of size 96
    private val embeddingBuffer = ArrayList<FloatArray>(32)

    @Volatile var averageInferenceLatencyMs: Long = 0
        private set
    private var totalInferenceCount = 0
    private var totalInferenceTimeMs: Long = 0
    @Volatile var lastProbability: Float = 0.0f
        private set

    private var logThrottleCounter = 0

    init {
        resetBuffers()
    }

    private fun resetBuffers() {
        rawAudioBuffer.clear()
        // Pre-fill history samples with silence
        for (i in 0 until HISTORY_SAMPLES) {
            rawAudioBuffer.add(0.0f)
        }

        melBuffer.clear()
        // openWakeWord standard initialization: 76 frames of ones
        for (i in 0 until MEL_FRAMES_REQUIRED) {
            melBuffer.add(FloatArray(MEL_BINS) { 1.0f })
        }

        embeddingBuffer.clear()
        // 16 frames of baseline embeddings
        for (i in 0 until EMBEDDING_FRAMES_REQUIRED) {
            embeddingBuffer.add(FloatArray(EMBEDDING_DIM) { 0.0f })
        }
    }

    override fun initialize(context: Context): Boolean {
        try {
            val melFile = copyAssetToFile(context, "melspectrogram.onnx")
            val embFile = copyAssetToFile(context, "embedding_model.onnx")
            val jarvisFile = copyAssetToFile(context, "hey_jarvis_v0.1.onnx")

            if (!melFile.exists() || !embFile.exists() || !jarvisFile.exists()) {
                Log.w(TAG, "ONNX model assets missing from storage")
                isInstalled = false
                return false
            }

            env = OrtEnvironment.getEnvironment()
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }

            melSession = env?.createSession(melFile.absolutePath, sessionOpts)
            embSession = env?.createSession(embFile.absolutePath, sessionOpts)
            jarvisSession = env?.createSession(jarvisFile.absolutePath, sessionOpts)

            isInstalled = melSession != null && embSession != null && jarvisSession != null
            resetBuffers()
            Log.i(TAG, "OnnxWakeWordEngine initialized successfully! isInstalled=$isInstalled")
            return isInstalled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OnnxWakeWordEngine: ${e.message}", e)
            isInstalled = false
            return false
        }
    }

    override fun start(onWakeWordDetected: (String) -> Unit) {
        resetBuffers()
        Log.i(TAG, "OnnxWakeWordEngine started, buffers initialized")
    }

    override fun stop() {
        resetBuffers()
        Log.i(TAG, "OnnxWakeWordEngine stopped, buffers cleared")
    }

    /**
     * Ingests 16-bit 16kHz mono audio frame.
     * Returns true when neural wake probability exceeds user-configured threshold.
     */
    @Synchronized
    override fun processAudioFrame(audioFrame: ShortArray): Boolean {
        if (!isInstalled || env == null) return false
        if (audioFrame.isEmpty()) return false

        var maxPeak = 0
        // 1. Ingest raw 16-bit PCM samples as Float (range ~ -32768.0f to 32767.0f)
        for (sample in audioFrame) {
            val absVal = kotlin.math.abs(sample.toInt())
            if (absVal > maxPeak) maxPeak = absVal
            rawAudioBuffer.add(sample.toFloat())
        }

        var detected = false

        // 2. Process complete 1280-sample steps with 480-sample history (1760 samples total)
        while (rawAudioBuffer.size >= TOTAL_WINDOW_SAMPLES) {
            val window1760 = FloatArray(TOTAL_WINDOW_SAMPLES)
            for (i in 0 until TOTAL_WINDOW_SAMPLES) {
                window1760[i] = rawAudioBuffer[i]
            }

            // Remove only the stepped 1280 samples, keeping 480 context for the next window
            rawAudioBuffer.subList(0, MEL_CHUNK_SAMPLES).clear()

            val stepDetected = runStreamingStep(window1760, maxPeak)
            if (stepDetected) {
                detected = true
                break
            }
        }

        return detected
    }

    @Suppress("UNCHECKED_CAST")
    private fun runStreamingStep(audioWindow1760: FloatArray, peak: Int): Boolean {
        val ortEnv = env ?: return false
        val mel = melSession ?: return false
        val emb = embSession ?: return false
        val jarvis = jarvisSession ?: return false

        val startTime = System.currentTimeMillis()
        try {
            // Step 1: Melspectrogram inference [1, 1760] -> [1, 1, 8, 32]
            val audioTensor = OnnxTensor.createTensor(ortEnv, arrayOf(audioWindow1760))
            val melResult = mel.run(mapOf("input" to audioTensor))
            val melRaw = melResult[0].value as Array<Array<Array<FloatArray>>>
            val timeSteps = melRaw[0][0].size // 8 frames

            // Apply openWakeWord transform: mel / 10 + 2
            for (t in 0 until timeSteps) {
                val frame = FloatArray(MEL_BINS) { b ->
                    melRaw[0][0][t][b] / 10.0f + 2.0f
                }
                melBuffer.add(frame)
                while (melBuffer.size > MEL_FRAMES_REQUIRED) {
                    melBuffer.removeAt(0)
                }
            }
            audioTensor.close()
            melResult.close()

            // Step 2: Speech Embedding inference [1, 76, 32, 1] -> [1, 1, 1, 96]
            val embInput = Array(1) { Array(MEL_FRAMES_REQUIRED) { f -> Array(MEL_BINS) { b -> FloatArray(1) { melBuffer[f][b] } } } }
            val embTensor = OnnxTensor.createTensor(ortEnv, embInput)
            val embResult = emb.run(mapOf("input_1" to embTensor))
            val embRaw = embResult[0].value as Array<Array<Array<FloatArray>>>
            val embedding96 = embRaw[0][0][0].clone()
            embTensor.close()
            embResult.close()

            embeddingBuffer.add(embedding96)
            while (embeddingBuffer.size > EMBEDDING_FRAMES_REQUIRED) {
                embeddingBuffer.removeAt(0)
            }

            // Step 3: Hey Jarvis Classifier inference [1, 16, 96] -> [1, 1] probability
            val jarvisInput = Array(1) { Array(EMBEDDING_FRAMES_REQUIRED) { e -> FloatArray(EMBEDDING_DIM) { d -> embeddingBuffer[e][d] } } }
            val jarvisTensor = OnnxTensor.createTensor(ortEnv, jarvisInput)
            val jarvisResult = jarvis.run(mapOf("x.1" to jarvisTensor))
            val probRaw = jarvisResult[0].value as Array<FloatArray>
            val prob = probRaw[0][0]
            lastProbability = prob
            jarvisTensor.close()
            jarvisResult.close()

            val latency = System.currentTimeMillis() - startTime
            totalInferenceCount++
            totalInferenceTimeMs += latency
            averageInferenceLatencyMs = totalInferenceTimeMs / totalInferenceCount

            logThrottleCounter++
            if (DEBUG_WAKE && (prob >= 0.15f || logThrottleCounter % 25 == 0)) {
                Log.d(TAG, "[DEBUG_WAKE] Audio: peak=$peak | Model prob=%.4f (threshold=0.50, latency=%dms, detected=%b)"
                    .format(prob, latency, prob >= 0.50f))
            }

            // Threshold evaluation (0.50f standard)
            val threshold = 0.50f
            if (prob >= threshold) {
                Log.i(TAG, "★ WAKE_DETECTED! Confirmed 'Jarvis' wake word (Prob: %.4f, Latency: %dms, Peak: %d)".format(prob, latency, peak))
                // Reset rolling buffers after detection
                resetBuffers()
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in streaming step: ${e.message}", e)
        }
        return false
    }

    private fun copyAssetToFile(context: Context, assetName: String): File {
        val outFile = File(context.filesDir, assetName)
        if (!outFile.exists() || outFile.length() == 0L) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }
}
