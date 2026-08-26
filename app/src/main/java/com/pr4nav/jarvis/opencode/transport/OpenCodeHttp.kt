package com.pr4nav.jarvis.opencode.transport

import com.pr4nav.jarvis.opencode.OpenCodeConfig
import com.pr4nav.jarvis.opencode.OpenCodeException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OpenCodeHttp(private val configProvider: () -> OpenCodeConfig) {

    val config: OpenCodeConfig get() = configProvider()

    sealed class RawResult {
        data class Ok(val status: Int, val body: String) : RawResult()
        data class Failed(val error: OpenCodeException) : RawResult()

        fun getOrThrow(): String = when (this) {
            is Ok -> body
            is Failed -> throw error
        }
    }

    fun url(path: String, query: List<Pair<String, String?>> = emptyList()): String {
        val base = config.baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        val sb = StringBuilder(base).append(p)
        val params = query.filter { it.second != null }
        if (params.isNotEmpty()) {
            sb.append('?')
            params.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append('&')
                sb.append(urlEncode(k)).append('=').append(urlEncode(v!!))
            }
        }
        return sb.toString()
    }

    private fun urlEncode(v: String): String {
        return java.net.URLEncoder.encode(v, "UTF-8")
    }

    fun getJson(path: String, query: List<Pair<String, String?>> = emptyList()): RawResult =
        execute("GET", path, query, null)

    fun postJson(path: String, body: JSONObject?, query: List<Pair<String, String?>> = emptyList()): RawResult =
        execute("POST", path, query, body)

    fun patchJson(path: String, body: JSONObject?, query: List<Pair<String, String?>> = emptyList()): RawResult {
        val cfg = config
        val url = url(path, query)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/json"
        headers["User-Agent"] = "jarvis-opencode/1.0"
        if (!cfg.password.isNullOrBlank()) {
            headers["Authorization"] =
                OpenCodeConfig.basicAuthHeader(cfg.effectiveUsername, cfg.password!!)
        }
        val payload = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        return try {
            val parsed = URL(url)
            val port = if (parsed.port > 0) parsed.port else parsed.defaultPort
            val (status, respBody) = socketRequest(
                host = parsed.host,
                port = port,
                pathAndQuery = parsed.file.ifBlank { "/" },
                method = "PATCH",
                headers = headers,
                bodyBytes = payload
            )
            if (status in 200..299) RawResult.Ok(status, respBody)
            else RawResult.Failed(mapHttpFailure(status, respBody, path))
        } catch (e: OpenCodeException) {
            RawResult.Failed(e)
        } catch (e: java.net.SocketTimeoutException) {
            RawResult.Failed(OpenCodeException.timeout("${e.message ?: "timeout"} [$path]"))
        } catch (e: IOException) {
            RawResult.Failed(
                OpenCodeException(OpenCodeException.Code.UNAVAILABLE, "OpenCode unreachable [$path]: ${e.message}", e)
            )
        } catch (e: Exception) {
            RawResult.Failed(
                OpenCodeException(OpenCodeException.Code.NETWORK_IO, "PATCH failed [$path]: ${e.message}", e)
            )
        }
    }

    private fun socketRequest(
        host: String,
        port: Int,
        pathAndQuery: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray
    ): Pair<Int, String> {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(host, port), config.connectTimeoutMs)
            sock.soTimeout = config.readTimeoutMs
            val out = sock.getOutputStream()
            val sb = StringBuilder()
            sb.append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append(':').append(port).append("\r\n")
            headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()
            val raw = readAll(sock.getInputStream())
            val headerEnd = raw.indexOf("\r\n\r\n")
            if (headerEnd < 0) throw OpenCodeException.malformed("Malformed HTTP response from server")
            val statusLine = raw.substring(0, raw.indexOf('\n')).trim()
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw OpenCodeException.malformed("Bad status line: $statusLine")
            var bodyPart = raw.substring(headerEnd + 4)
            val lowerHeaders = raw.substring(0, headerEnd).lowercase()
            if (lowerHeaders.contains("transfer-encoding: chunked")) {
                bodyPart = dechunk(bodyPart)
            } else {
                Regex("content-length:\\s*(\\d+)").find(lowerHeaders)?.groupValues?.get(1)?.let { lenStr ->
                    bodyPart = bodyPart.take(lenStr.toIntOrNull() ?: bodyPart.length)
                }
            }
            return status to bodyPart
        }
    }

    private fun dechunk(raw: String): String {
        var data = raw
        val out = StringBuilder()
        while (data.isNotEmpty()) {
            val lineEnd = data.indexOf("\r\n")
            if (lineEnd < 0) break
            val sizeHex = data.substring(0, lineEnd).trim().substringBefore(';')
            val size = sizeHex.toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            val end = minOf(start + size, data.length)
            out.append(data, start, end)
            data = data.drop(end + 2)
        }
        return out.toString()
    }

    fun deleteJson(path: String, query: List<Pair<String, String?>> = emptyList()): RawResult =
        execute("DELETE", path, query, null)

    fun execute(
        method: String,
        path: String,
        query: List<Pair<String, String?>>,
        body: JSONObject?
    ): RawResult {
        val cfg = config
        val connection = open(cfg, method, path, query)
        try {
            if (body != null) {
                connection.doOutput = true
                setRequestJson(connection)
                connection.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            }
            val status = connection.responseCode
            if (status in 200..299) {
                return RawResult.Ok(status, readAll(connection.inputStream))
            }
            val errBody = try {
                readAll(connection.errorStream ?: connection.inputStream)
            } catch (_: Exception) {
                ""
            }
            return RawResult.Failed(mapHttpFailure(status, errBody, path))
        } catch (e: OpenCodeException) {
            return RawResult.Failed(e)
        } catch (e: java.net.SocketTimeoutException) {
            return RawResult.Failed(OpenCodeException.timeout("${e.message ?: "timeout"} [$path]"))
        } catch (e: IOException) {
            return RawResult.Failed(
                OpenCodeException(OpenCodeException.Code.UNAVAILABLE, "OpenCode unreachable at ${cfg.baseUrl} [$path]: ${e.message}", e)
            )
        } catch (e: Exception) {
            return RawResult.Failed(
                OpenCodeException(OpenCodeException.Code.NETWORK_IO, "Request failed [$path]: ${e.message}", e)
            )
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun parseObject(raw: RawResult): OcParsed<JSONObject> = when (raw) {
        is RawResult.Ok -> try {
            OcParsed.Ok(JSONObject(raw.body))
        } catch (e: Exception) {
            OcParsed.Failed(OpenCodeException.malformed("Non-JSON object response [${raw.status}]: ${raw.body.take(160)}", e))
        }
        is RawResult.Failed -> OcParsed.Failed(raw.error)
    }

    fun parseArray(raw: RawResult): OcParsed<JSONArray> = when (raw) {
        is RawResult.Ok -> try {
            OcParsed.Ok(JSONArray(raw.body))
        } catch (e: Exception) {
            OcParsed.Failed(OpenCodeException.malformed("Non-JSON array response [${raw.status}]: ${raw.body.take(160)}", e))
        }
        is RawResult.Failed -> OcParsed.Failed(raw.error)
    }

    sealed class OcParsed<out T> {
        data class Ok<T>(val value: T) : OcParsed<T>()
        data class Failed(val error: OpenCodeException) : OcParsed<Nothing>()

        @Suppress("UNCHECKED_CAST")
        fun getOrThrow(): T = when (this) {
            is Ok -> value
            is Failed -> throw error
        }
    }

    companion object {
        internal fun mapHttpFailure(status: Int, body: String, path: String): OpenCodeException {
            val snippet = body.take(240).ifBlank { "(empty)" }
            val code = when {
                status == 401 || status == 403 -> OpenCodeException.Code.AUTH
                status == 404 -> OpenCodeException.Code.NOT_FOUND
                status == 409 -> OpenCodeException.Code.BUSY
                status == 400 || status == 422 -> OpenCodeException.Code.BAD_REQUEST
                status >= 500 -> OpenCodeException.Code.SERVER
                else -> OpenCodeException.Code.MALFORMED
            }
            return OpenCodeException(code, "HTTP $status on $path: $snippet", httpStatus = status, detail = snippet)
        }

        internal fun open(
            cfg: OpenCodeConfig,
            method: String,
            pathOrUrl: String,
            query: List<Pair<String, String?>> = emptyList()
        ): HttpURLConnection {
            val full = if (pathOrUrl.startsWith("http")) {
                appendQuery(pathOrUrl, query)
            } else {
                val base = cfg.baseUrl.trimEnd('/')
                val p = if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
                appendQuery("$base$p", query)
            }
            val conn = URL(full).openConnection() as HttpURLConnection
            conn.connectTimeout = cfg.connectTimeoutMs
            conn.readTimeout = cfg.readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "jarvis-opencode/1.0")
            setRequestMethodCompat(conn, method)
            if (!cfg.password.isNullOrBlank()) {
                conn.setRequestProperty(
                    "Authorization",
                    OpenCodeConfig.basicAuthHeader(cfg.effectiveUsername, cfg.password)
                )
            }
            return conn
        }

        private fun setRequestMethodCompat(conn: HttpURLConnection, method: String) {
            try {
                conn.requestMethod = method
            } catch (e: java.net.ProtocolException) {
                if (method != "PATCH") throw e
                try {
                    val methodsField = HttpURLConnection::class.java.getDeclaredField("methods")
                    methodsField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val existing = methodsField.get(null) as Array<String>
                    if (existing.none { it.equals(method, ignoreCase = true) }) {
                        val extended = existing.copyOf(existing.size + 1)
                        extended[existing.size] = method
                        methodsField.set(null, extended)
                    }
                } catch (_: Throwable) {
                }
                conn.requestMethod = method
            }
        }

        internal fun setRequestJson(conn: HttpURLConnection) {
            conn.setRequestProperty("Content-Type", "application/json")
        }

        private fun appendQuery(url: String, query: List<Pair<String, String?>>): String {
            val params = query.filter { it.second != null }
            if (params.isEmpty()) return url
            val sep = if (url.contains('?')) "&" else "?"
            return url + sep + params.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v!!, "UTF-8")
            }
        }

        internal fun readAll(stream: java.io.InputStream?): String {
            if (stream == null) return ""
            return stream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        }
    }
}
