package com.pr4nav.jarvis.llm

/**
 * Dependency-free Base64 (RFC 4648, standard alphabet) so WebSocket framing
 * and Live API payloads work on every API level and stay JVM-testable.
 */
object WsBase64 {

    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INV = IntArray(128).apply {
        fill(-1)
        ALPHA.forEachIndexed { i, c -> this[c.code] = i }
        this['='.code] = 0
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHA[b0 shr 2])
            sb.append(ALPHA[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) ALPHA[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHA[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.filter { it.code < 128 && (INV[it.code] >= 0) }
        if (clean.isEmpty()) return ByteArray(0)
        val pad = if (clean.endsWith("==")) 2 else if (clean.endsWith("=")) 1 else 0
        val out = ByteArray(clean.length * 3 / 4 - pad)
        var oi = 0
        var i = 0
        while (i < clean.length) {
            val c0 = INV[clean[i].code]
            val c1 = INV[clean[i + 1].code]
            val c2 = if (i + 2 < clean.length) INV[clean[i + 2].code] else 0
            val c3 = if (i + 3 < clean.length) INV[clean[i + 3].code] else 0
            out[oi++] = ((c0 shl 2) or (c1 shr 4)).toByte()
            if (oi < out.size) out[oi++] = (((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte()
            if (oi < out.size) out[oi++] = (((c2 and 0x03) shl 6) or c3).toByte()
            i += 4
        }
        return out
    }
}
