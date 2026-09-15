package com.pr4nav.jarvis.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini 2.5 Flash Native Audio Dialog via the Live API (bidirectional WS).
 *
 * - Text in/out, image in (JPEG inlineData), mic audio in (16k PCM),
 *   model audio out (24k PCM — inbuilt TTS), function calling out.
 * - Key: reuses the Gemini provider key.
 * - Testing playground grade: one session at a time, manual connect.
 */
object GeminiLiveClient {

    private const val TAG = "GeminiLive"
    const val LIVE_HOST = "generativelanguage.googleapis.com"
    const val LIVE_PATH = "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

    const val MODEL_DIALOG = "models/gemini-2.5-flash-preview-native-audio-dialog"
    const val MODEL_DIALOG_THINKING = "models/gemini-2.5-flash-exp-native-audio-thinking-dialog"

    /** Live models (no list endpoint — the dialog models). */
    val LIVE_MODELS = listOf(MODEL_DIALOG, MODEL_DIALOG_THINKING)

    private const val PREFS_NAME = "jarvis_live_prefs"
    private const val KEY_MODEL = "live_model"

    fun getSelectedModel(context: Context?): String {
        if (context == null) return MODEL_DIALOG
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODEL, MODEL_DIALOG) ?: MODEL_DIALOG
        } catch (_: Exception) {
            MODEL_DIALOG
        }
    }

    fun setSelectedModel(context: Context?, model: String) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL, model.trim()).apply()
        } catch (_: Exception) { }
    }

    fun shortLabel(modelId: String): String = when (modelId) {
        MODEL_DIALOG -> "Native Audio Dialog"
        MODEL_DIALOG_THINKING -> "Native Audio Thinking"
        else -> modelId.substringAfterLast("/").substringAfterLast(":")
    }

    const val OUT_SAMPLE_RATE = 24000
    const val IN_SAMPLE_RATE = 16000

    interface Listener {
        fun onStatus(text: String)
        fun onTextDelta(text: String)
        fun onTurnDone(fullText: String)
        fun onAudioChunk(pcm16: ByteArray)
        fun onImage(mime: String, bytes: ByteArray)
        fun onInterrupted()
        fun onToolCall(id: String, name: String, args: String)
        fun onClosed(reason: String)
        fun onError(err: String)
    }

    data class ToolCall(val id: String, val name: String, val args: String)

    data class ParsedServerMessage(
        val setupComplete: Boolean = false,
        val textDelta: String = "",
        val audioPcm: ByteArray? = null,
        val images: List<Pair<String, ByteArray>> = emptyList(),
        val turnComplete: Boolean = false,
        val interrupted: Boolean = false,
        val toolCalls: List<ToolCall> = emptyList(),
        val error: String = ""
    )

    @Volatile private var socket: LiveSocket? = null
    @Volatile private var connected = false
    @Volatile private var setupDone = false
    private var listener: Listener? = null
    private val textBuffer = StringBuilder()
    private val lock = Any()

    fun isConnected(): Boolean = connected && setupDone

    // ── Pure message builders / parsers (unit-tested) ─────────────────────────

    fun buildSetup(
        model: String,
        audioResponses: Boolean,
        systemText: String,
        tools: JSONArray?
    ): String {
        val genConfig = JSONObject().apply {
            put("responseModalities", JSONArray().apply {
                put(if (audioResponses) "AUDIO" else "TEXT")
            })
        }
        val setup = JSONObject().apply {
            put("model", model)
            put("generationConfig", genConfig)
            if (systemText.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().apply {
                        put(
                            "parts",
                            JSONArray().apply {
                                put(JSONObject().put("text", systemText))
                            }
                        )
                    }
                )
            }
            if (tools != null) put("tools", tools)
        }
        return JSONObject().put("setup", setup).toString()
    }

    fun buildTools(): JSONArray {
        fun decl(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("description", desc)
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        put("required", JSONArray(required))
                    }
                )
            }
        }
        fun str(desc: String) = JSONObject().apply {
            put("type", "string")
            put("description", desc)
        }
        val runShell = decl(
            "run_shell",
            "Run a shell command on the Android device via Termux sh and return stdout/stderr.",
            JSONObject().apply {
                put("command", str("Exact shell command to run"))
            },
            listOf("command")
        )
        val readFile = decl(
            "read_file",
            "Read a text file from device storage and return its contents.",
            JSONObject().apply {
                put("path", str("Absolute file path"))
            },
            listOf("path")
        )
        val getTime = decl(
            "get_time",
            "Get the current device date and time.",
            JSONObject(), emptyList()
        )
        val takeScreenshot = decl(
            "take_screenshot",
            "Capture the current device screen and return it as an image. Use when the user asks what is on screen.",
            JSONObject(), emptyList()
        )
        return JSONArray().apply {
            put(JSONObject().put("functionDeclarations", JSONArray().apply {
                put(runShell)
                put(readFile)
                put(getTime)
                put(takeScreenshot)
            }))
        }
    }

    fun buildTextTurn(
        text: String,
        imageBase64Jpeg: String? = null,
        videoFramesJpeg: List<String> = emptyList()
    ): String {
        val parts = JSONArray().apply {
            if (imageBase64Jpeg != null) {
                put(
                    JSONObject().apply {
                        put(
                            "inlineData",
                            JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", imageBase64Jpeg)
                            }
                        )
                    }
                )
            }
            videoFramesJpeg.take(10).forEachIndexed { idx, frame ->
                put(
                    JSONObject().apply {
                        put(
                            "inlineData",
                            JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", frame)
                            }
                        )
                    }
                )
                if (idx == 0) {
                    put(JSONObject().put("text", "[video frames follow, 1 per second]"))
                }
            }
            put(JSONObject().put("text", text))
        }
        return JSONObject().apply {
            put(
                "clientContent",
                JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", parts)
                        })
                    })
                    put("turnComplete", true)
                }
            )
        }.toString()
    }

    fun buildAudioChunk(base64Pcm16: String): String {
        return JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "mediaChunks",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("mimeType", "audio/pcm;rate=16000")
                                    put("data", base64Pcm16)
                                }
                            )
                        }
                    )
                }
            )
        }.toString()
    }

    fun buildToolResponse(id: String, name: String, result: JSONObject): String {
        return JSONObject().apply {
            put(
                "toolCallResponse",
                JSONObject().apply {
                    put(
                        "functionResponses",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("id", id)
                                    put("name", name)
                                    put("response", JSONObject().apply {
                                        put("result", result)
                                    })
                                }
                            )
                        }
                    )
                }
            )
        }.toString()
    }

    fun parseServerMessage(raw: String): ParsedServerMessage {
        try {
            val root = JSONObject(raw)
            if (root.has("setupComplete")) return ParsedServerMessage(setupComplete = true)
            root.optJSONObject("serverContent")?.let { sc ->
                if (sc.optBoolean("interrupted", false)) {
                    return ParsedServerMessage(interrupted = true)
                }
                var text = ""
                var audio: ByteArray? = null
                val images = mutableListOf<Pair<String, ByteArray>>()
                val turn = sc.optJSONObject("modelTurn")
                if (turn != null) {
                    val parts = turn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val p = parts.optJSONObject(i) ?: continue
                            val t = p.optString("text", "")
                            if (t.isNotBlank()) text += t
                            val inline = p.optJSONObject("inlineData")
                            if (inline != null) {
                                val b64 = inline.optString("data", "")
                                val mime = inline.optString("mimeType", "")
                                if (b64.isNotBlank()) {
                                    try {
                                        val bytes = WsBase64.decode(b64)
                                        if (bytes.isNotEmpty()) {
                                            if (mime.startsWith("audio")) {
                                                audio = if (audio == null) bytes else audio + bytes
                                            } else {
                                                images.add(mime.ifBlank { "image/jpeg" } to bytes)
                                            }
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                        }
                    }
                }
                val done = sc.optBoolean("turnComplete", false)
                return ParsedServerMessage(
                    textDelta = text,
                    audioPcm = audio,
                    images = images,
                    turnComplete = done
                )
            }
            root.optJSONObject("toolCall")?.let { tc ->
                val calls = mutableListOf<ToolCall>()
                val arr = tc.optJSONArray("functionCalls")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        calls.add(
                            ToolCall(
                                id = f.optString("id", "call-$i"),
                                name = f.optString("name", ""),
                                args = (f.optJSONObject("args") ?: JSONObject()).toString()
                            )
                        )
                    }
                }
                if (calls.isNotEmpty()) return ParsedServerMessage(toolCalls = calls)
            }
            val errObj = root.optJSONObject("error")
            if (errObj != null) {
                return ParsedServerMessage(
                    error = "${errObj.optInt("code", -1)}: ${errObj.optString("message", "unknown")}"
                )
            }
            return ParsedServerMessage()
        } catch (e: Exception) {
            return ParsedServerMessage(error = "parse: ${e.message}")
        }
    }

    // ── Session ───────────────────────────────────────────────────────────────

    fun connect(
        context: Context,
        model: String = MODEL_DIALOG,
        audioResponses: Boolean = true,
        withTools: Boolean = true,
        listener: Listener
    ) {
        disconnect("reconnect")
        val apiKey = GeminiCloudLLM.getApiKey(context)
        if (apiKey.isBlank()) {
            listener.onError("Gemini API key missing — add it in Provider keys.")
            return
        }
        this.listener = listener
        textBuffer.clear()
        val systemText = "You are JARVIS, a terse on-device co-pilot. Keep replies short. " +
            "You can run device shell commands and read files via tools when useful."
        val sock = LiveSocket()
        socket = sock
        sock.listener = object : LiveSocket.Listener {
            override fun onOpen() {
                try {
                    sock.sendText(
                        buildSetup(model, audioResponses, systemText, if (withTools) buildTools() else null)
                    )
                    listener.onStatus("Connected — waiting for setup…")
                } catch (e: Exception) {
                    listener.onError("Setup send failed: ${e.message}")
                }
            }

            override fun onText(text: String) {
                handleIncoming(text)
            }

            override fun onClose(code: Int, reason: String) {
                connected = false
                setupDone = false
                listener.onStatus("Disconnected ($code)")
                listener.onClosed("Closed $code $reason")
            }

            override fun onError(e: Exception) {
                listener.onError(e.message ?: "socket error")
            }
        }
        try {
            listener.onStatus("Connecting…")
            sock.connect(
                LIVE_HOST, 443,
                "$LIVE_PATH?key=$apiKey"
            )
            connected = true
        } catch (e: Exception) {
            connected = false
            listener.onError("Connect failed: ${e.message}")
        }
    }

    fun sendText(
        text: String,
        imageBase64Jpeg: String? = null,
        videoFramesJpeg: List<String> = emptyList()
    ) {
        val s = socket
        if (s == null || !connected) {
            listener?.onError("Not connected")
            return
        }
        synchronized(lock) { textBuffer.clear() }
        try {
            s.sendText(buildTextTurn(text, imageBase64Jpeg, videoFramesJpeg))
        } catch (e: Exception) {
            listener?.onError("Send failed: ${e.message}")
        }
    }

    fun sendAudioChunk(base64Pcm16: String) {
        try {
            socket?.sendText(buildAudioChunk(base64Pcm16))
        } catch (_: Exception) { }
    }

    fun respondTool(id: String, name: String, result: JSONObject) {
        try {
            socket?.sendText(buildToolResponse(id, name, result))
        } catch (e: Exception) {
            listener?.onError("Tool response failed: ${e.message}")
        }
    }

    fun disconnect(reason: String = "user") {
        connected = false
        setupDone = false
        try {
            socket?.sendClose()
        } catch (_: Exception) { }
        try {
            socket?.close()
        } catch (_: Exception) { }
        socket = null
        Log.i(TAG, "Disconnected: $reason")
    }

    private fun handleIncoming(raw: String) {
        val l = listener ?: return
        val msg = try {
            parseServerMessage(raw)
        } catch (e: Exception) {
            l.onError("Parse failed: ${e.message}")
            return
        }
        if (msg.error.isNotBlank()) {
            l.onError(msg.error)
            return
        }
        if (msg.setupComplete) {
            setupDone = true
            l.onStatus("Live — speak or type")
            return
        }
        if (msg.interrupted) {
            synchronized(lock) { textBuffer.clear() }
            l.onInterrupted()
            return
        }
        if (msg.textDelta.isNotBlank()) {
            synchronized(lock) { textBuffer.append(msg.textDelta) }
            l.onTextDelta(msg.textDelta)
        }
        msg.audioPcm?.let { l.onAudioChunk(it) }
        for ((mime, bytes) in msg.images) {
            l.onImage(mime, bytes)
        }
        for (tc in msg.toolCalls) {
            l.onToolCall(tc.id, tc.name, tc.args)
        }
        if (msg.turnComplete) {
            val full = synchronized(lock) {
                val s = textBuffer.toString()
                textBuffer.clear()
                s
            }
            l.onTurnDone(full)
        }
    }

    // ── One-shot turn for route-engine use (connect → text → wait → close) ────

    data class TurnResult(
        val success: Boolean,
        val text: String,
        val thinkingTrace: String = "",
        val error: String? = null
    )

    fun oneShotTurn(
        context: Context,
        prompt: String,
        timeoutSec: Long = 60L
    ): TurnResult {
        val latch = CountDownLatch(1)
        val outText = StringBuilder()
        var err: String? = null
        val gotSetup = AtomicBoolean(false)
        val queue = ConcurrentLinkedQueue<String>()
        connect(
            context = context,
            model = MODEL_DIALOG,
            audioResponses = false,
            withTools = false,
            listener = object : Listener {
                override fun onStatus(text: String) {
                    if (text.startsWith("Live")) gotSetup.set(true)
                }

                override fun onTextDelta(text: String) {
                    queue.add(text)
                }

                override fun onTurnDone(fullText: String) {
                    outText.append(fullText)
                    latch.countDown()
                }

                override fun onAudioChunk(pcm16: ByteArray) {}
                override fun onImage(mime: String, bytes: ByteArray) {}
                override fun onInterrupted() {}
                override fun onToolCall(id: String, name: String, args: String) {}
                override fun onClosed(reason: String) {
                    if (latch.count > 0 && outText.isEmpty() && queue.isEmpty()) {
                        err = "closed early: $reason"
                        latch.countDown()
                    }
                }

                override fun onError(e: String) {
                    err = e
                    latch.countDown()
                }
            }
        )
        // Wait for setup, then send.
        val setupDeadline = System.currentTimeMillis() + 15_000
        while (!gotSetup.get() && System.currentTimeMillis() < setupDeadline && err == null) {
            Thread.sleep(100)
        }
        if (err == null && gotSetup.get()) {
            sendText(prompt)
        } else if (err == null) {
            err = "setup timed out"
        }
        val done = latch.await(timeoutSec, TimeUnit.SECONDS)
        disconnect("oneshot")
        val drained = StringBuilder()
        while (true) {
            drained.append(queue.poll() ?: break)
        }
        val text = (outText.toString() + drained.toString()).trim()
        if (!done && text.isBlank()) return TurnResult(false, "", error = err ?: "turn timed out")
        if (text.isBlank()) return TurnResult(false, "", error = err ?: "empty reply")
        return TurnResult(true, text)
    }
}
