package com.pr4nav.jarvis.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Real on-device network timing to the Kira backend.
 * Measures raw TCP connect, TLS handshake and HTTP TTFB so socket
 * timeouts and latency budgets are tuned from data, not guesses.
 */
@RunWith(AndroidJUnit4::class)
class NetProbeTest {

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    @Test fun probeKiraHandshake() {
        // 1. Raw TCP connect to 443.
        var t0 = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress("kiraai.vn", 443), 10_000)
            assertTrue(s.isConnected)
        }
        val tcpMs = ms(t0)
        println("PROBE tcp_connect_ms=$tcpMs")

        // 2. TLS + HTTP GET /api/v1/models (401/402 without key is fine — timing is the datum).
        t0 = System.nanoTime()
        var code = -1
        var bodyBytes = 0
        try {
            val conn = (URL("https://kiraai.vn/api/v1/models").openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            bodyBytes = stream?.use { it.readBytes().size } ?: 0
        } catch (e: Exception) {
            println("PROBE https_error=${e.javaClass.simpleName}:${e.message}")
        }
        val httpMs = ms(t0)
        println("PROBE https_total_ms=$httpMs code=$code bytes=$bodyBytes")

        // Budgets: fail loudly if the path itself is unusable.
        assertTrue("TCP connect impossibly slow: ${tcpMs}ms", tcpMs < 10_000)
        assertTrue("HTTPS round trip impossibly slow: ${httpMs}ms", httpMs < 15_000)
    }
}
