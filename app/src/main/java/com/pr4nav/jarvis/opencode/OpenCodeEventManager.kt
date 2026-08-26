package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcEvent
import com.pr4nav.jarvis.opencode.json.OcEvents
import com.pr4nav.jarvis.opencode.transport.OpenCodeSse
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OpenCodeEventManager(
    private val configSupplier: () -> OpenCodeConfig,
    private val client: OpenCodeClient
) {

    interface Listener {
        fun onEvent(event: OcEvent)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)

    @Volatile
    var directoryFilter: Set<String>? = null

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var lastEventAtMs: Long = 0L
        private set

    var onConnectionChanged: ((connected: Boolean) -> Unit)? = null
    var onReconnected: (() -> Unit)? = null

    private var thread: Thread? = null

    private val deltaLastEmit = HashMap<String, Long>()
    private val deltaLock = Object()
    private val deltaThrottleMs: Long = 100

    fun subscribe(listener: Listener) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: Listener) {
        listeners.remove(listener)
    }

    fun listenerCount(): Int = listeners.size

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "opencode-events").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        setConnected(false)
    }

    fun awaitConnected(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connected) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun loop() {
        val myGen = generation.incrementAndGet()
        var attempt = 0L
        while (running.get() && generation.get() == myGen) {
            var delay: Long
            try {
                connectOnce(myGen)
                attempt = 0
                delay = 250
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                attempt++
                if (attempt == 1L || attempt % 5 == 0L) {
                    OpenCodeLogger.w(
                        TAG,
                        "event stream down (attempt $attempt): ${e.message}"
                    )
                }
                delay = backoffDelay(attempt)
            }
            if (!running.get() || generation.get() != myGen) break
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    internal fun backoffDelay(attempt: Long): Long {
        val raw = configSupplier().reconnectBaseDelayMs * (1L shl ((attempt - 1).toInt().coerceAtMost(10)))
        return raw.coerceIn(0L, configSupplier().reconnectMaxDelayMs)
    }

    private fun connectOnce(myGen: Long) {
        val cfg = configSupplier()
        val wasConnected = connected
        val sse = OpenCodeSse(configSupplier, "/global/event")
        val watchdog = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(5_000)
                    val idleFor = System.currentTimeMillis() - sse.lastActivityAtMs
                    if (idleFor > cfg.sseIdleTimeoutMs) {
                        OpenCodeLogger.w(TAG, "SSE idle ${idleFor}ms — forcing reconnect")
                        sse.close()
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "opencode-sse-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
        try {
            OpenCodeLogger.d(TAG, "connecting SSE → ${cfg.baseUrl}/global/event")
            sse.run(object : OpenCodeSse.Listener {
                override fun onFrame(eventName: String?, data: String) {
                    if (generation.get() != myGen || !running.get()) return
                    if (eventName == OpenCodeSse.FRAME_OPEN) {
                        if (!wasConnected) OpenCodeLogger.i(TAG, "SSE connected")
                        setConnected(true)
                        return
                    }
                    lastEventAtMs = System.currentTimeMillis()
                    val event = try {
                        OcEvents.decode(JSONObject(data))
                    } catch (e: Exception) {
                        OpenCodeLogger.w(TAG, "undecodable event frame: ${data.take(120)}")
                        null
                    } ?: return
                    if (!passesFilter(event)) return
                    dispatch(event)
                }

                override fun onClosed(clean: Boolean, error: OpenCodeException?) {
                    setConnected(false)
                    if (!clean && error != null) {
                        throw error
                    }
                }
            })
        } finally {
            watchdog.interrupt()
        }
        onReconnected?.invoke()
    }

    private fun passesFilter(event: OcEvent): Boolean {
        val filter = directoryFilter ?: return true
        val dir = event.directory ?: return true
        return filter.any { f -> dir.endsWith(f.trimEnd('/')) || f.endsWith(dir.trimEnd('/')) }
    }

    private fun dispatch(event: OcEvent) {
        val deliverable = throttleIfNeeded(event)
        if (deliverable == null) return
        for (l in listeners) {
            try {
                l.onEvent(deliverable)
            } catch (e: Exception) {
                OpenCodeLogger.w(TAG, "listener error: ${e.message}")
            }
        }
    }

    private fun throttleIfNeeded(event: OcEvent): OcEvent? {
        if (event !is OcEvent.PartDelta) return event
        val key = "${event.sessionId}/${event.messageId}/${event.partId}"
        synchronized(deltaLock) {
            val now = System.currentTimeMillis()
            val last = deltaLastEmit[key] ?: 0L
            if (now - last < deltaThrottleMs) {
                return null
            }
            deltaLastEmit[key] = now
            if (deltaLastEmit.size > 512) deltaLastEmit.clear()
            return event
        }
    }

    private fun setConnected(value: Boolean) {
        if (connected != value) {
            connected = value
            onConnectionChanged?.invoke(value)
        }
    }

    companion object {
        const val TAG = "Events"
    }
}
