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
    private val onVoiceActivityCandidate: () -> Unit = {}
) {

    companion object {
        private const val TAG = "AcousticWakeDetector"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RMS_THRESHOLD = 2000.0 // Energy threshold separating silence from intentional speech
        private const val CONSECUTIVE_VOICE_FRAMES = 5 // ~200ms continuous vocal energy required
    }

    @Volatile private var isRunning = false
    private var workerThread: Thread? = null

    fun start() {
        if (isRunning) return

        val hasMic = context.checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
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
    }

    private fun runDetectionLoop() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE / 10) // 100ms chunk
        val audioBuffer = ShortArray(bufferSize)

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            val record = audioRecord ?: return
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord initialization failed")
                return
            }

            record.startRecording()
            Log.i(TAG, "AudioRecord started successfully! Streaming to wakeWordEngine: ${wakeWordEngine?.name} (installed=${wakeWordEngine?.isInstalled})")
            var voiceFrameCount = 0
            var frameLogged = false

            while (isRunning && !Thread.currentThread().isInterrupted) {
                val readShorts = record.read(audioBuffer, 0, audioBuffer.size)
                if (readShorts > 0) {
                    if (!frameLogged) {
                        Log.i(TAG, "Successfully read first PCM frame ($readShorts shorts)")
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
                            Log.i(TAG, "Native WakeWordEngine confirmed 'Jarvis' (RMS: %.1f)".format(rms))
                            isRunning = false
                            try {
                                record.stop()
                                record.release()
                            } catch (_: Exception) {}
                            audioRecord = null
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Acoustic detector exception: ${e.message}", e)
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {}
        }
    }
}
