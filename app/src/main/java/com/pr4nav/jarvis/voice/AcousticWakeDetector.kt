package com.pr4nav.jarvis.voice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.sqrt

/**
 * Lightweight, Low-Power Background Acoustic Monitor & Voice Activity Detector (VAD).
 *
 * NOTE: This is an acoustic candidate detector (VAD), NOT a wake-word classifier.
 * It measures vocal energy bursts to detect candidate speech.
 * Frames are forwarded to the pluggable WakeWordEngine for local verification before
 * starting SpeechRecognizer.
 */
class AcousticWakeDetector(
    private val context: Context,
    private val wakeWordEngine: WakeWordEngine? = null,
    private val onWakeWordDetected: (wakeWord: String) -> Unit,
    private val onVoiceActivityCandidate: () -> Unit = {},
    private val onDetectorDied: () -> Unit = {}
) {

    companion object {
        private const val TAG = "AcousticWakeDetector"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RMS_THRESHOLD = 800.0 // Energy threshold separating silence from intentional speech
        private const val CONSECUTIVE_VOICE_FRAMES = 4 // ~160ms continuous vocal energy required
    }

    @Volatile private var isRunning = false
    @Volatile private var activeRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    fun isListening(): Boolean {
        val rec = activeRecord
        return isRunning && workerThread?.isAlive == true && rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    fun start() {
        if (isRunning) return

        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            Log.w(TAG, "Cannot start AcousticWakeDetector: RECORD_AUDIO permission not granted")
            return
        }

        isRunning = true
        workerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            runDetectionLoop()
        }, "JarvisAcousticDetector").apply { start() }
    }

    fun stop() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
        try {
            activeRecord?.stop()
            activeRecord?.release()
            activeRecord = null
        } catch (_: Exception) {}
    }

    private fun runDetectionLoop() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSizeInBytes = maxOf(if (minBufferSize > 0) minBufferSize * 2 else 4096, 4096)
        val chunkShorts = 1280 // 80ms chunk at 16kHz matches openWakeWord frame size
        val audioBuffer = ShortArray(chunkShorts)

        while (isRunning && !Thread.currentThread().isInterrupted) {
            var audioRecord: AudioRecord? = null
            var retryCount = 0

            while (isRunning && !Thread.currentThread().isInterrupted && audioRecord == null && retryCount < 15) {
                val audioSources = intArrayOf(
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT
                )

                for (source in audioSources) {
                    try {
                        val candidate = AudioRecord(
                            source,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufferSizeInBytes
                        )
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            audioRecord = candidate
                            Log.i(TAG, "AudioRecord initialized successfully (source=$source, bufferSize=$bufferSizeInBytes)")
                            break
                        } else {
                            candidate.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord init failed for source=$source: ${e.message}")
                    }
                }

                if (audioRecord == null) {
                    retryCount++
                    Log.w(TAG, "AudioRecord init deferred (attempt $retryCount/15). Retrying in 1000ms...")
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }

            val record = audioRecord ?: run {
                Log.e(TAG, "AudioRecord failed to initialize after retries. Retrying cycle in 2000ms...")
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
                continue
            }

            activeRecord = record

            try {
                record.startRecording()
                Log.i(TAG, "AudioRecord streaming active! Engine: ${wakeWordEngine?.name} (installed=${wakeWordEngine?.isInstalled})")
                var voiceFrameCount = 0
                var frameLogged = false
                var consecutiveErrors = 0

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val readShorts = record.read(audioBuffer, 0, audioBuffer.size)

                    if (readShorts <= 0) {
                        consecutiveErrors++
                        if (consecutiveErrors % 5 == 0) {
                            Log.w(TAG, "AudioRecord read returned $readShorts (consecutive: $consecutiveErrors)")
                        }
                        Thread.sleep(60)
                        if (consecutiveErrors >= 10) {
                            Log.w(TAG, "Excessive consecutive read errors, re-initializing AudioRecord...")
                            break
                        }
                        continue
                    }

                    consecutiveErrors = 0

                    if (!frameLogged) {
                        Log.i(TAG, "Successfully captured live PCM frame ($readShorts shorts)")
                        frameLogged = true
                    }

                    var sumSquares = 0.0
                    for (i in 0 until readShorts) {
                        val sample = audioBuffer[i].toDouble()
                        sumSquares += sample * sample
                    }
                    val rms = sqrt(sumSquares / readShorts)

                    // Continuously feed neural wake-word engine if installed
                    if (wakeWordEngine?.isInstalled == true) {
                        val slice = if (readShorts == audioBuffer.size) audioBuffer else audioBuffer.copyOf(readShorts)
                        val detected = wakeWordEngine.processAudioFrame(slice)
                        if (detected) {
                            Log.i(TAG, "★ Native WakeWordEngine confirmed 'Jarvis' (RMS: %.1f)".format(rms))
                            isRunning = false
                            try {
                                record.stop()
                                record.release()
                            } catch (_: Exception) {}
                            activeRecord = null
                            onWakeWordDetected("Jarvis")
                            return
                        }
                    }

                    if (rms > RMS_THRESHOLD) {
                        voiceFrameCount++
                        if (voiceFrameCount >= CONSECUTIVE_VOICE_FRAMES) {
                            voiceFrameCount = 0
                            onVoiceActivityCandidate()
                        }
                    } else {
                        voiceFrameCount = maxOf(0, voiceFrameCount - 1)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Acoustic detector exception: ${e.message}", e)
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {}
                activeRecord = null
            }

            if (isRunning) {
                Log.i(TAG, "AudioRecord loop ended while isRunning=true; restarting cycle after 500ms...")
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }

        if (isRunning) {
            isRunning = false
            onDetectorDied()
        }
    }
}
