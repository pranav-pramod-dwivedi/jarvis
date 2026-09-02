package com.pr4nav.jarvis.agy

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AgyClient(private val config: AgyConfig = AgyConfig()) {

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class Health(
        val ok: Boolean,
        val running: Boolean,
        val version: String?,
        val port: Int,
        val busy: Boolean
    )

    private fun postCallback(block: () -> Unit) {
        val isUnitTest = try {
            Class.forName("org.junit.Test") != null
        } catch (_: Throwable) {
            false
        }
        if (isUnitTest) {
            block()
        } else {
            try {
                mainHandler.post(block)
            } catch (_: Throwable) {
                block()
            }
        }
    }

    fun checkHealth(onSuccess: (Health) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            try {
                // Probe root / (official Antigravity Hub UI or custom API server)
                val url = URL("${config.baseUrl.trimEnd('/')}/")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = config.connectTimeoutMs
                    readTimeout = config.connectTimeoutMs
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code in 200..399 || code == 401 || code == 403) {
                    val h = Health(
                        ok = true,
                        running = true,
                        version = "2.11.0",
                        port = config.port,
                        busy = false
                    )
                    postCallback { onSuccess(h) }
                } else {
                    postCallback { onError("HTTP $code from AGY daemon") }
                }
            } catch (e: Exception) {
                postCallback { onError(e.message ?: "AGY daemon unreachable") }
            }
        }
    }

    interface StreamHandle {
        fun cancel()
    }

    fun sendPromptStream(
        prompt: String,
        model: String? = null,
        mode: String? = null,
        onToken: (String) -> Unit,
        onStep: (String) -> Unit,
        onComplete: (fullResponse: String) -> Unit,
        onError: (String) -> Unit
    ): StreamHandle {
        var cancelled = false
        var activeConn: HttpURLConnection? = null

        val future = executor.submit {
            val fullText = StringBuilder()
            try {
                val url = URL("${config.baseUrl.trimEnd('/')}/api/prompt")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = config.connectTimeoutMs
                    readTimeout = config.readTimeoutMs
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                }
                activeConn = conn

                val body = JSONObject().apply {
                    put("prompt", prompt)
                    if (!model.isNullOrBlank() && model != "default") put("model", model)
                    if (!mode.isNullOrBlank() && mode != "default") put("mode", mode)
                    put("continue", true)
                    put("skip_permissions", true)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    var line: String? = null
                    while (!cancelled && reader.readLine().also { line = it } != null) {
                        val l = line?.trim() ?: continue
                        if (!l.startsWith("data: ")) continue
                        val payload = l.substring(6).trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue

                        try {
                            val json = JSONObject(payload)
                            val ev = json.optString("event")
                            if (ev == "step_update") {
                                val su = json.optJSONObject("step_update")
                                if (su != null) {
                                    val delta = su.optString("text_delta")
                                    if (delta.isNotEmpty()) {
                                        fullText.append(delta)
                                        mainHandler.post { onToken(delta) }
                                    }
                                    val st = su.optString("step_type")
                                    val state = su.optString("state")
                                    if (st.isNotEmpty() && state.isNotEmpty()) {
                                        mainHandler.post { onStep("[$st: $state]") }
                                    }
                                }
                            } else if (ev == "result") {
                                val res = json.optJSONObject("result")
                                val finalResp = res?.optString("response")
                                if (!finalResp.isNullOrEmpty() && fullText.isEmpty()) {
                                    fullText.append(finalResp)
                                    mainHandler.post { onToken(finalResp) }
                                }
                            } else if (ev == "error") {
                                val err = json.optString("error")
                                mainHandler.post { onError(err) }
                            }
                        } catch (_: Exception) {
                            if (payload.isNotEmpty()) {
                                fullText.append(payload)
                                mainHandler.post { onToken(payload) }
                            }
                        }
                    }
                }

                if (!cancelled) {
                    if (fullText.isEmpty()) {
                        Log.i("AgyClient", "SSE port ${config.port} returned no tokens, executing via PRoot AGY CLI directly...")
                        mainHandler.post { onStep("[⚡ PRoot Linux AGY Engine]") }
                        val agyRes = com.pr4nav.jarvis.Shell.agy(prompt, timeoutMs = 60_000)
                        if (agyRes.out.isNotBlank()) {
                            val words = agyRes.out.split(" ")
                            for (w in words) {
                                if (cancelled) break
                                mainHandler.post { onToken("$w ") }
                                try { Thread.sleep(15) } catch (_: Exception) {}
                            }
                            mainHandler.post { onComplete(agyRes.out) }
                        } else {
                            mainHandler.post { onError(if (agyRes.err.isNotBlank()) agyRes.err else "AGY daemon unavailable") }
                        }
                    } else {
                        mainHandler.post { onComplete(fullText.toString()) }
                    }
                }
            } catch (e: Exception) {
                if (!cancelled) {
                    Log.i("AgyClient", "Port ${config.port} connection error (${e.message}), executing via PRoot AGY CLI directly...")
                    mainHandler.post { onStep("[⚡ PRoot Linux AGY Engine]") }
                    val agyRes = com.pr4nav.jarvis.Shell.agy(prompt, timeoutMs = 60_000)
                    if (agyRes.out.isNotBlank()) {
                        val words = agyRes.out.split(" ")
                        for (w in words) {
                            if (cancelled) break
                            mainHandler.post { onToken("$w ") }
                            try { Thread.sleep(15) } catch (_: Exception) {}
                        }
                        mainHandler.post { onComplete(agyRes.out) }
                    } else {
                        mainHandler.post { onError(if (agyRes.err.isNotBlank()) agyRes.err else (e.message ?: "Stream failure")) }
                    }
                }
            } finally {
                activeConn = null
            }
        }

        return object : StreamHandle {
            override fun cancel() {
                cancelled = true
                future.cancel(true)
                try { activeConn?.disconnect() } catch (_: Exception) {}
                abort()
            }
        }
    }

    fun abort(onDone: (() -> Unit)? = null) {
        executor.execute {
            try {
                val url = URL("${config.baseUrl.trimEnd('/')}/api/abort")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 3000
                    doOutput = true
                }
                conn.responseCode
            } catch (_: Exception) {}
            onDone?.let { mainHandler.post(it) }
        }
    }

    fun exec(cmd: String, onResult: (out: String, err: String, rc: Int) -> Unit) {
        executor.execute {
            try {
                val url = URL("${config.baseUrl.trimEnd('/')}/api/exec")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = config.connectTimeoutMs
                    readTimeout = config.readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject().apply { put("cmd", cmd) }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                if (code in 200..299) {
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(raw)
                    val out = json.optString("stdout", "")
                    val err = json.optString("stderr", "")
                    val rc = json.optInt("rc", 0)
                    mainHandler.post { onResult(out, err, rc) }
                } else {
                    mainHandler.post { onResult("", "HTTP $code", -1) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult("", e.message ?: "Exec error", -1) }
            }
        }
    }
}
