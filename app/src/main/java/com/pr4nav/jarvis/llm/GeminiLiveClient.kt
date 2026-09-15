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
    // v1beta path verified against the working reference playground.
    const val LIVE_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    const val MODEL_DIALOG = "models/gemini-2.5-flash-native-audio-preview-09-2025"
    const val MODEL_DIALOG_12 = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    const val MODEL_LEGACY = "models/gemini-2.0-flash-live-001"
    const val MODEL_DIALOG_THINKING = "models/gemini-2.5-flash-exp-native-audio-thinking-dialog"

    /** Live models (no list endpoint — the dialog family). */
    val LIVE_MODELS = listOf(MODEL_DIALOG, MODEL_DIALOG_12, MODEL_LEGACY)

    /** Output voices (reference list). */
    val VOICES = listOf(
        "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
        "Despina", "Erinome", "Sadachbia", "Schedar", "Sulafat", "Duende"
    )

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
        MODEL_DIALOG -> "Native Audio 09-2025"
        MODEL_DIALOG_12 -> "Native Audio 12-2025"
        MODEL_LEGACY -> "Live 2.0 Legacy"
        MODEL_DIALOG_THINKING -> "Native Audio Thinking"
        else -> modelId.substringAfterLast("/").substringAfterLast(":")
    }

    const val OUT_SAMPLE_RATE = 24000
    const val IN_SAMPLE_RATE = 16000

    interface Listener {
        fun onStatus(text: String)
        fun onTextDelta(text: String)
        fun onThought(text: String) {}
        fun onTurnDone(fullText: String)
        fun onAudioChunk(pcm16: ByteArray)
        fun onImage(mime: String, bytes: ByteArray)
        fun onUserTranscript(text: String)
        fun onInterrupted()
        fun onToolCall(id: String, name: String, args: String)
        fun onClosed(reason: String)
        fun onError(err: String)
    }

    data class ToolCall(val id: String, val name: String, val args: String)

    data class ParsedServerMessage(
        val setupComplete: Boolean = false,
        val textDelta: String = "",
        val thoughtDelta: String = "",
        val audioPcm: ByteArray? = null,
        val images: List<Pair<String, ByteArray>> = emptyList(),
        val inputTranscript: String = "",
        val outputTranscript: String = "",
        val goAway: Boolean = false,
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
    /** Last sent payload kind + preview — surfaced when the server 1007s, so the culprit is visible. */
    @Volatile private var lastSentKind = "none"
    @Volatile private var lastSentPreview = ""

    fun lastSentReport(): String = "$lastSentKind: ${lastSentPreview.take(220)}"

    private fun trackSent(kind: String, payload: String) {
        lastSentKind = kind
        lastSentPreview = payload.take(400)
    }

    fun isConnected(): Boolean = connected && setupDone

    // ── Pure message builders / parsers (unit-tested) ─────────────────────────

    fun buildSetup(
        model: String,
        audioResponses: Boolean,
        systemText: String,
        tools: JSONArray?,
        voice: String = "Aoede",
        inputTranscription: Boolean = true,
        outputTranscription: Boolean = true
    ): String {
        val genConfig = JSONObject().apply {
            put("responseModalities", JSONArray().apply {
                put(if (audioResponses) "AUDIO" else "TEXT")
            })
            put(
                "speechConfig",
                JSONObject().apply {
                    put(
                        "voiceConfig",
                        JSONObject().apply {
                            put(
                                "prebuiltVoiceConfig",
                                JSONObject().apply { put("voiceName", voice) }
                            )
                        }
                    )
                }
            )
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
            if (inputTranscription) put("inputAudioTranscription", JSONObject())
            if (outputTranscription) put("outputAudioTranscription", JSONObject())
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
                        "audio",
                        JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Pcm16)
                        }
                    )
                }
            )
        }.toString()
    }

    /** End the mic stream before a text turn (mixing both triggers server 1007). */
    fun buildAudioStreamEnd(): String {
        return JSONObject().apply {
            put("realtimeInput", JSONObject().put("audioStreamEnd", true))
        }.toString()
    }

    fun buildVideoChunk(base64Jpeg: String): String {
        return JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "video",
                        JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Jpeg)
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
                var thought = ""
                var audio: ByteArray? = null
                val images = mutableListOf<Pair<String, ByteArray>>()
                val turn = sc.optJSONObject("modelTurn")
                if (turn != null) {
                    val parts = turn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val p = parts.optJSONObject(i) ?: continue
                            val t = p.optString("text", "")
                            if (t.isNotBlank()) {
                                // Thought parts are reasoning, not the answer.
                                if (p.optBoolean("thought", false)) thought += t
                                else text += t
                            }
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
                val inTr = sc.optJSONObject("inputTranscription")?.optString("text", "").orEmpty()
                val outTr = sc.optJSONObject("outputTranscription")?.optString("text", "").orEmpty()
                return ParsedServerMessage(
                    textDelta = text,
                    thoughtDelta = thought,
                    audioPcm = audio,
                    images = images,
                    inputTranscript = inTr,
                    outputTranscript = outTr,
                    turnComplete = done
                )
            }
            if (root.optJSONObject("goAway") != null) {
                return ParsedServerMessage(goAway = true)
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
        voice: String = "Autonoe",
        systemText: String = "You are JARVIS, a concise realtime assistant. Keep replies to 1-3 sentences. Answer directly; never speak internal reasoning aloud.",
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
        val sock = LiveSocket()
        socket = sock
        sock.listener = object : LiveSocket.Listener {
            override fun onOpen() {
                try {
                    val setupPayload = buildSetup(
                        model, audioResponses, systemText,
                        if (withTools) buildTools() else null, voice
                    )
                    trackSent("setup", setupPayload)
                    sock.sendText(setupPayload)
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
                var fullReason = "Closed $code $reason".trim()
                if (code == 1007) {
                    fullReason += " — server rejected a message. Last sent [${lastSentReport()}]"
                }
                listener.onStatus("Disconnected ($code)")
                listener.onClosed(fullReason)
            }

            override fun onError(e: Exception) {
                listener.onError(e.message ?: "socket error")
            }
        }
        try {
            listener.onStatus("Connecting…")
            sock.connect(
                LIVE_HOST, 443,
                "$LIVE_PATH?key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
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
            val payload = buildTextTurn(text, imageBase64Jpeg, videoFramesJpeg)
            trackSent(
                "clientContent",
                "text='${text.take(80)}' images=${imageBase64Jpeg != null} frames=${videoFramesJpeg.size}"
            )
            s.sendText(payload)
        } catch (e: Exception) {
            listener?.onError("Send failed: ${e.message}")
        }
    }

    fun sendAudioChunk(base64Pcm16: String) {
        try {
            socket?.sendText(buildAudioChunk(base64Pcm16))
        } catch (_: Exception) { }
    }

    fun sendAudioStreamEnd() {
        try {
            socket?.sendText(buildAudioStreamEnd())
        } catch (_: Exception) { }
    }

    fun sendVideoFrame(base64Jpeg: String) {
        try {
            socket?.sendText(buildVideoChunk(base64Jpeg))
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
        if (msg.inputTranscript.isNotBlank()) {
            l.onUserTranscript(msg.inputTranscript)
        }
        if (msg.outputTranscript.isNotBlank()) {
            synchronized(lock) { textBuffer.append(msg.outputTranscript) }
            l.onTextDelta(msg.outputTranscript)
        }
        if (msg.goAway) {
            l.onStatus("Server goAway — reconnect soon")
            return
        }
        if (msg.textDelta.isNotBlank()) {
            synchronized(lock) { textBuffer.append(msg.textDelta) }
            l.onTextDelta(msg.textDelta)
        }
        if (msg.thoughtDelta.isNotBlank()) {
            l.onThought(msg.thoughtDelta)
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
        val thoughtText = StringBuilder()
        var err: String? = null
        val gotSetup = AtomicBoolean(false)
        val queue = ConcurrentLinkedQueue<String>()
        LiveAudioPlayer.stop() // never overlap a previous turn's voice
        // NOTE: AUDIO modality even though we only want text — the native-audio
        // dialog models reject TEXT-only setups (server 1007). The reply text
        // arrives via output transcription; audio plays through LiveAudioPlayer.
        connect(
            context = context,
            model = MODEL_DIALOG,
            audioResponses = true,
            withTools = false,
            voice = "Kore",
            listener = object : Listener {
                override fun onStatus(text: String) {
                    if (text.startsWith("Live")) gotSetup.set(true)
                }

                override fun onTextDelta(text: String) {
                    queue.add(text)
                }

                override fun onThought(text: String) {
                    thoughtText.append(text)
                }

                override fun onTurnDone(fullText: String) {
                    outText.append(fullText)
                    latch.countDown()
                }

                override fun onAudioChunk(pcm16: ByteArray) {
                    LiveAudioPlayer.play(pcm16)
                }
                override fun onImage(mime: String, bytes: ByteArray) {}
                override fun onUserTranscript(text: String) {}
                override fun onInterrupted() {}
                override fun onToolCall(id: String, name: String, args: String) {}
                override fun onClosed(reason: String) {
                    if (latch.count > 0 && outText.isEmpty() && queue.isEmpty()) {
                        err = if (!gotSetup.get()) {
                            "Live setup rejected: $reason — check model/voice/modality"
                        } else {
                            "closed early: $reason"
                        }
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
        val thought = thoughtText.toString().trim()
        if (!done && text.isBlank()) return TurnResult(false, "", thought, error = err ?: "turn timed out")
        if (text.isBlank()) return TurnResult(false, "", thought, error = err ?: "empty reply")
        return TurnResult(true, text, thought)
    }
}
