package com.pr4nav.jarvis.llm

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared 24kHz PCM playback for Live model audio (inbuilt TTS).
 * Dedicated queue thread: socket reading never stalls behind audio writes,
 * and a paused/flushed track is always revived (never silent reuse).
 */
object LiveAudioPlayer {

    private const val TAG = "LiveAudioPlayer"
    const val SAMPLE_RATE = 24000

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null

    @Synchronized
    private fun ensureWorker() {
        if (running.getAndSet(true)) return
        worker = Thread({
            while (running.get()) {
                try {
                    val chunk = queue.take()
                    writeChunk(chunk)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "playback: ${e.message}")
                }
            }
        }, "live-audio-player").apply { isDaemon = true; start() }
    }

    private fun writeChunk(pcm16: ByteArray) {
        try {
            var t = track
            if (t == null || t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    t?.release()
                } catch (_: Exception) { }
                val minBuf = try {
                    AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(8192)
                } catch (_: Exception) {
                    16384
                }
                t = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4,
                    AudioTrack.MODE_STREAM
                )
                track = t
            }
            t.play()
            t.write(pcm16, 0, pcm16.size)
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
        }
    }

    /** Queue a chunk (non-blocking). Starts the worker on first use. */
    fun play(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        ensureWorker()
        // Drop backlog beyond ~3s so live speech never lags minutes behind.
        while (queue.size > 30) {
            queue.poll()
        }
        queue.offer(pcm16)
    }

    /** Stop now: clear backlog, release the track. Next play() rebuilds. */
    @Synchronized
    fun stop() {
        running.set(false)
        try {
            worker?.interrupt()
        } catch (_: Exception) { }
        worker = null
        queue.clear()
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) { }
        track = null
    }
}
