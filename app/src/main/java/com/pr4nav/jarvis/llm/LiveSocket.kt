package com.pr4nav.jarvis.llm

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Minimal WebSocket client (RFC 6455) over TLS. No dependencies:
 * text frames out (masked), text/ping/pong/close handling in.
 * Enough for JSON APIs like Gemini Live.
 */
class LiveSocket {

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClose(code: Int, reason: String)
        fun onError(e: Exception)
    }

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var readerThread: Thread? = null
    private val closed = AtomicBoolean(false)
    /** ALL wire writes go through this lock — an interleaved ping/text corrupts frames (server 1007). */
    private val writeLock = Any()
    var listener: Listener? = null

    fun buildUrl(host: String, pathAndQuery: String, apiKey: String): String {
        val enc = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val sep = if (pathAndQuery.contains("?")) "&" else "?"
        return "wss://$host$pathAndQuery${sep}key=$enc"
    }

    fun connect(host: String, port: Int, pathAndQuery: String, extraHeaders: Map<String, String> = emptyMap()) {
        close()
        closed.set(false)
        val keyBytes = Random.nextBytes(16)
        val key = WsBase64.encode(keyBytes)
        val sock = SSLSocketFactory.getDefault().createSocket(host, port)
        socket = sock
        val w = sock.getOutputStream().bufferedWriter(Charsets.US_ASCII)
        w.write("GET $pathAndQuery HTTP/1.1\r\n")
        w.write("Host: $host\r\n")
        w.write("Upgrade: websocket\r\n")
        w.write("Connection: Upgrade\r\n")
        w.write("Sec-WebSocket-Key: $key\r\n")
        w.write("Sec-WebSocket-Version: 13\r\n")
        for ((k, v) in extraHeaders) w.write("$k: $v\r\n")
        w.write("\r\n")
        w.flush()

        // Byte-wise header read: a BufferedReader may swallow post-header frame bytes.
        val rawIn = sock.getInputStream()
        val status = readAsciiLine(rawIn) ?: throw java.io.IOException("Empty handshake response")
        if (!status.contains("101")) throw java.io.IOException("Handshake failed: $status")
        var acceptOk = false
        val expected = acceptKey(key)
        while (true) {
            val line = readAsciiLine(rawIn) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) &&
                line.substringAfter(":").trim() == expected
            ) {
                acceptOk = true
            }
        }
        if (!acceptOk) throw java.io.IOException("Bad accept key")
        out = sock.getOutputStream()

        readerThread = Thread({
            try {
                listener?.onOpen()
                readLoop(sock.getInputStream())
            } catch (e: Exception) {
                if (!closed.get()) listener?.onError(e)
            }
        }, "live-socket-reader").apply { isDaemon = true; start() }
    }

    fun sendText(text: String) {
        val o = out ?: throw java.io.IOException("Not connected")
        synchronized(writeLock) {
            o.write(encodeTextFrame(text))
            o.flush()
        }
    }

    fun sendClose(code: Int = 1000) {
        try {
            val o = out ?: return
            val payload = byteArrayOf((code shr 8).toByte(), code.toByte())
            synchronized(writeLock) {
                o.write(encodeFrame(0x8, payload, mask = true))
                o.flush()
            }
        } catch (_: Exception) { }
        close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            readerThread?.interrupt()
        } catch (_: Exception) { }
        try {
            socket?.close()
        } catch (_: Exception) { }
        socket = null
        out = null
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = try {
                input.read()
            } catch (_: Exception) {
                return null
            }
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    private fun readLoop(input: InputStream) {
        val din = DataInputStream(input)
        val frag = StringBuilder()
        var fragOp = -1
        while (!closed.get()) {
            val frame = decodeFrame(din) ?: break
            when (frame.opcode) {
                0x8 -> {
                    listener?.onClose(frame.code, "")
                    close()
                    return
                }
                0x9 -> {
                    // ping → pong (under the write lock: never interleave a text frame)
                    try {
                        val o = out
                        if (o != null) {
                            synchronized(writeLock) {
                                o.write(encodeFrame(0xA, frame.payload, mask = true))
                                o.flush()
                            }
                        }
                    } catch (_: Exception) { }
                }
                0xA -> { /* pong */ }
                0x1, 0x0 -> {
                    if (frame.opcode == 0x1) {
                        frag.clear()
                        fragOp = 0x1
                    }
                    if (fragOp == 0x1 || frame.opcode == 0x0) {
                        frag.append(String(frame.payload, Charsets.UTF_8))
                    }
                    if (frame.fin && fragOp == 0x1) {
                        fragOp = -1
                        listener?.onText(frag.toString())
                    }
                }
                0x2 -> {
                    // Binary frames carrying JSON text (browser parity: blob.text()).
                    try {
                        listener?.onText(String(frame.payload, Charsets.UTF_8))
                    } catch (_: Exception) { }
                }
            }
        }
    }

    data class DecodedFrame(
        val fin: Boolean,
        val opcode: Int,
        val payload: ByteArray,
        val code: Int = 1000
    )

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun acceptKey(clientKey: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            val hash = md.digest((clientKey + GUID).toByteArray(Charsets.US_ASCII))
            return WsBase64.encode(hash)
        }

        fun encodeTextFrame(text: String): ByteArray =
            encodeFrame(0x1, text.toByteArray(Charsets.UTF_8), mask = true)

        fun encodeFrame(opcode: Int, payload: ByteArray, mask: Boolean): ByteArray {
            val maskKey = if (mask) Random.nextBytes(4) else ByteArray(0)
            val headerSize = 2 + (if (payload.size > 65535) 8 else if (payload.size > 125) 2 else 0) +
                (if (mask) 4 else 0)
            val out = ByteArray(headerSize + payload.size)
            out[0] = (0x80 or opcode).toByte()
            var pos = 2
            if (payload.size > 65535) {
                out[1] = ((if (mask) 0x80 else 0x00) or 127).toByte()
                val len = payload.size.toLong()
                for (i in 7 downTo 0) out[pos++] = ((len ushr (i * 8)) and 0xFF).toByte()
            } else if (payload.size > 125) {
                out[1] = ((if (mask) 0x80 else 0x00) or 126).toByte()
                out[pos++] = ((payload.size ushr 8) and 0xFF).toByte()
                out[pos++] = (payload.size and 0xFF).toByte()
            } else {
                out[1] = ((if (mask) 0x80 else 0x00) or payload.size).toByte()
            }
            if (mask) {
                System.arraycopy(maskKey, 0, out, pos, 4)
                pos += 4
                for (i in payload.indices) {
                    out[pos + i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            } else {
                System.arraycopy(payload, 0, out, pos, payload.size)
            }
            return out
        }

        fun decodeFrame(din: DataInputStream): DecodedFrame? {
            val b0: Int
            val b1: Int
            try {
                b0 = din.readUnsignedByte()
                b1 = din.readUnsignedByte()
            } catch (_: Exception) {
                return null
            }
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = din.readUnsignedShort().toLong()
            } else if (len == 127L) {
                len = din.readLong()
                if (len < 0 || len > 16_000_000L) throw java.io.IOException("Frame too large")
            }
            val maskKey = if (masked) ByteArray(4).also { din.readFully(it) } else null
            if (len > 16_000_000L) throw java.io.IOException("Frame too large")
            val payload = ByteArray(len.toInt())
            din.readFully(payload)
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }
            var code = 1000
            if (opcode == 0x8 && payload.size >= 2) {
                code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            }
            return DecodedFrame(fin, opcode, payload, code)
        }
    }
}
