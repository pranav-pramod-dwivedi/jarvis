package com.pr4nav.jarvis.opencode.transport

import com.pr4nav.jarvis.opencode.OpenCodeConfig
import com.pr4nav.jarvis.opencode.OpenCodeException
import com.pr4nav.jarvis.opencode.OpenCodeLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection

class OpenCodeSse(
    private val configProvider: () -> OpenCodeConfig,
    private val pathOrUrl: String,
    private val query: List<Pair<String, String?>> = emptyList()
) {
    interface Listener {
        fun onFrame(eventName: String?, data: String)
        fun onClosed(clean: Boolean, error: OpenCodeException?)
    }

    @Volatile
    var lastActivityAtMs: Long = System.currentTimeMillis()
        private set

    @Volatile
    private var conn: HttpURLConnection? = null

    @Volatile
    private var closedByOwner = false

    private val closeLock = Any()

    fun close() {
        synchronized(closeLock) {
            closedByOwner = true
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    fun run(listener: Listener) {
        val cfg = configProvider()
        var http: HttpURLConnection? = null
        try {
            http = OpenCodeHttp.Companion.open(cfg, "GET", pathOrUrl, query)
            synchronized(closeLock) { if (!closedByOwner) conn = http }
            http.connectTimeout = cfg.sseConnectTimeoutMs
            http.readTimeout = 0
            http.setRequestProperty("Accept", "text/event-stream")
            http.setRequestProperty("Cache-Control", "no-cache")
            val code = http.responseCode
            if (code !in 200..299) {
                val body = try {
                    OpenCodeHttp.readAll(http.errorStream ?: http.inputStream)
                } catch (_: Exception) {
                    ""
                }
                throw OpenCodeHttp.mapHttpFailure(code, body, pathOrUrl)
            }
            val reader = BufferedReader(InputStreamReader(http.inputStream, Charsets.UTF_8))
            val dataLines = StringBuilder()
            var eventName: String? = null
            markActivity()
            listener.onFrame("open", "{}")
            while (true) {
                val line = reader.readLine() ?: break
                markActivity()
                when {
                    line.isEmpty() -> {
                        if (dataLines.isNotEmpty()) {
                            val data = dataLines.toString()
                            dataLines.setLength(0)
                            val ev = eventName
                            eventName = null
                            try {
                                listener.onFrame(ev, data)
                            } catch (e: Exception) {
                                OpenCodeLogger.w("sse", "listener threw: ${e.message}")
                            }
                        }
                    }
                    line.startsWith(":") -> Unit
                    line.startsWith("data:") -> {
                        if (dataLines.isNotEmpty()) dataLines.append('\n')
                        dataLines.append(line.removePrefix("data:").removePrefix(" "))
                    }
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("id:") || line.startsWith("retry:") -> Unit
                }
            }
            listener.onClosed(true, null)
        } catch (e: OpenCodeException) {
            listener.onClosed(false, e)
        } catch (_: javax.net.ssl.SSLException) {
            listener.onClosed(false, OpenCodeException(OpenCodeException.Code.NETWORK_IO, "SSE TLS failure"))
        } catch (e: java.net.SocketException) {
            if (closedByOwner) listener.onClosed(true, null)
            else listener.onClosed(
                false,
                OpenCodeException(OpenCodeException.Code.UNAVAILABLE, "SSE connection lost: ${e.message}", e)
            )
        } catch (e: java.io.IOException) {
            if (closedByOwner) listener.onClosed(true, null)
            else listener.onClosed(
                false,
                OpenCodeException(OpenCodeException.Code.UNAVAILABLE, "SSE stream ended abruptly: ${e.message}", e)
            )
        } catch (e: Exception) {
            listener.onClosed(false, OpenCodeException(OpenCodeException.Code.NETWORK_IO, "SSE error: ${e.message}", e))
        } finally {
            try {
                http?.disconnect()
            } catch (_: Exception) {
            }
            synchronized(closeLock) { conn = null }
        }
    }

    private fun markActivity() {
        lastActivityAtMs = System.currentTimeMillis()
    }

    companion object {
        const val FRAME_OPEN = "open"
    }
}
