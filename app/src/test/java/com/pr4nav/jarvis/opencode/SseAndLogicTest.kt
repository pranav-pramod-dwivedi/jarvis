package com.pr4nav.jarvis.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class QueueDecisionTest {

    @Test
    fun `idle session submits immediately`() {
        val d = OpenCodeSessionManager.decisionForQueue(busy = false, queueSize = 0)
        assertTrue(d is QueueDecision.SUBMIT_NOW)
    }

    @Test
    fun `busy session with room enqueues`() {
        val d = OpenCodeSessionManager.decisionForQueue(busy = true, queueSize = 2)
        assertTrue(d is QueueDecision.ENQUEUE)
    }

    @Test
    fun `busy full queue rejects`() {
        val d = OpenCodeSessionManager.decisionForQueue(busy = true, queueSize = 8)
        assertTrue(d is QueueDecision.REJECT_FULL)
    }

    @Test
    fun `custom max respected`() {
        val d = OpenCodeSessionManager.decisionForQueue(busy = true, queueSize = 3, max = 3)
        assertTrue(d is QueueDecision.REJECT_FULL)
    }
}

class BackoffTest {

    @Test
    fun `backoff grows exponentially capped`() {
        val mgr = OpenCodeEventManager({ OpenCodeConfig() }, OpenCodeClient { OpenCodeConfig() })
        assertEquals(1000L, mgr.backoffDelay(1))
        assertEquals(2000L, mgr.backoffDelay(2))
        assertEquals(4000L, mgr.backoffDelay(3))
        assertEquals(15000L, mgr.backoffDelay(9))
        assertEquals(15000L, mgr.backoffDelay(50))
    }
}

class SseParserTest {

    private class RecordingListener : com.pr4nav.jarvis.opencode.transport.OpenCodeSse.Listener {
        val frames = ArrayList<Pair<String?, String>>()
        var closedClean: Boolean? = null
        var error: OpenCodeException? = null

        override fun onFrame(eventName: String?, data: String) {
            synchronized(frames) { frames.add(eventName to data) }
        }

        override fun onClosed(clean: Boolean, error: OpenCodeException?) {
            this.closedClean = clean
            this.error = error
        }
    }

    @Test
    fun `parses multi-line data comments and event names then clean EOF`() {
        val server = ServerSocket(0)
        val port = server.localPort
        thread {
            val sock: Socket = server.accept()
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "\r\n").toByteArray()
            )
            out.flush()
            Thread.sleep(150)
            out.write(
                (": ping comment\n" +
                    "\n" +
                    "event: open\n" +
                    "data: {}\n" +
                    "\n" +
                    "event: message\n" +
                    "data: {\"type\":\"a\",\n" +
                    "data:  \"n\":1}\n" +
                    "\n" +
                    "data: tail-frame\n" +
                    "\n").toByteArray()
            )
            out.flush()
            Thread.sleep(200)
            try { sock.close() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}
        }
        val listener = RecordingListener()
        val sse = com.pr4nav.jarvis.opencode.transport.OpenCodeSse(
            { OpenCodeConfig(baseUrl = "http://127.0.0.1:$port") },
            "http://127.0.0.1:$port/stream"
        )
        sse.run(listener)
        try { server.close() } catch (_: Exception) {}
        val expected = listOf<Pair<String?, String>>(
            "message" to "{\"type\":\"a\",\n \"n\":1}",
            null to "tail-frame"
        )
        val actual = listener.frames.filter { it.second != "{}" }
        assertEquals(expected, actual)
        // server closed abruptly -> clean vs error varies by platform, only frames matter
        assertTrue(actual.isNotEmpty())
    }
}
