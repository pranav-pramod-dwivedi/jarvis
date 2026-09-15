package com.pr4nav.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pr4nav.jarvis.chat.ChatUi
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.llm.GeminiLiveClient
import com.pr4nav.jarvis.llm.WsBase64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Testing playground for Gemini 2.5 Flash Native Audio Dialog (Live API).
 * Text + image + video + mic in both directions, model audio out via
 * inbuilt TTS, tool calls executed on-device.
 */
class LivePlaygroundActivity : AppCompatActivity() {

    private lateinit var scroller: ScrollView
    private lateinit var transcript: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var btnConnect: android.widget.Button
    private lateinit var btnModality: android.widget.Button
    private lateinit var btnTools: android.widget.Button
    private lateinit var btnMic: android.widget.Button
    private lateinit var input: EditText

    private var audioResponses = true
    private var toolsOn = false
    private var voiceIdx = GeminiLiveClient.VOICES.indexOf("Autonoe").coerceAtLeast(0)
    private var lastSendAt = 0L

    private var player: AudioTrack? = null
    private var recorder: AudioRecord? = null
    private var micStreaming = false
    private var pendingImageB64: String? = null
    private var pendingVideoFrames: List<String> = emptyList()

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            try {
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    pendingImageB64 = jpegBase64(bmp, 1024, 80)
                    pendingVideoFrames = emptyList()
                    runOnUiThread {
                        note("Image attached (${pendingImageB64?.length ?: 0} chars). Type a message and send.")
                        Toast.makeText(this, "Image attached", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { note("Image attach failed: ${e.message}") }
            }
        }
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        thread {
            try {
                val frames = extractVideoFrames(uri.toString())
                if (frames.isNotEmpty()) {
                    pendingVideoFrames = frames
                    pendingImageB64 = null
                    runOnUiThread {
                        note("${frames.size} video frames attached. Type a message and send.")
                        Toast.makeText(this, "${frames.size} frames attached", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread { note("No frames extracted from that video.") }
                }
            } catch (e: Exception) {
                runOnUiThread { note("Video attach failed: ${e.message}") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_playground)

        scroller = findViewById(R.id.live_scroller)
        transcript = findViewById(R.id.live_transcript)
        statusView = findViewById(R.id.live_status)
        btnConnect = findViewById(R.id.btn_connect)
        btnModality = findViewById(R.id.btn_modality)
        btnTools = findViewById(R.id.btn_tools)
        findViewById<View>(R.id.btn_voice)?.setOnClickListener { cycleVoice() }
        btnMic = findViewById(R.id.btn_mic)
        input = findViewById(R.id.live_input)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btn_live_keys)?.setOnClickListener {
            startActivity(Intent(this, ProviderKeysActivity::class.java))
        }
        btnConnect.setOnClickListener { toggleConnect() }
        btnModality.setOnClickListener {
            audioResponses = !audioResponses
            btnModality.text = if (audioResponses) "AUDIO" else "TEXT"
            note("Response modality: ${if (audioResponses) "AUDIO (inbuilt TTS)" else "TEXT"}. Reconnect to apply.")
        }
        btnTools.setOnClickListener {
            toolsOn = !toolsOn
            btnTools.text = if (toolsOn) "TOOLS ON" else "TOOLS OFF"
            note("Tools ${if (toolsOn) "enabled" else "disabled"}. Reconnect to apply.")
        }
        findViewById<View>(R.id.btn_attach)?.setOnClickListener { pickMedia() }
        findViewById<View>(R.id.live_send)?.setOnClickListener { sendCurrent() }
        input.setOnEditorActionListener { _, _, _ ->
            sendCurrent()
            true
        }
        btnMic.setOnClickListener { toggleMic() }

        val keyOk = GeminiCloudLLM.getApiKey(this).isNotBlank()
        note(
            if (keyOk) "Gemini key found. Model: native-audio-dialog. Tap CONNECT."
            else "No Gemini key — tap KEYS to add one, then CONNECT."
        )
    }

    override fun onDestroy() {
        stopMic()
        stopPlayback()
        try {
            GeminiLiveClient.disconnect("destroy")
        } catch (_: Exception) { }
        super.onDestroy()
    }

    // ── connection ────────────────────────────────────────────────────────────

    private fun cycleVoice() {
        voiceIdx = (voiceIdx + 1) % GeminiLiveClient.VOICES.size
        val v = GeminiLiveClient.VOICES[voiceIdx]
        findViewById<android.widget.Button>(R.id.btn_voice)?.text = "VOICE · ${v.uppercase()}"
        note("Voice: $v. Reconnect to apply.")
    }

    private fun currentVoice(): String =
        GeminiLiveClient.VOICES[voiceIdx % GeminiLiveClient.VOICES.size]

    private fun toggleConnect() {        if (GeminiLiveClient.isConnected()) {
            GeminiLiveClient.disconnect("user")
            stopPlayback()
            stopMic()
            setStatus("Disconnected", "#94A3B8")
            btnConnect.text = "CONNECT"
            return
        }
        setStatus("Connecting…", "#F0ABFC")
        thread {
            GeminiLiveClient.connect(
                context = this,
                model = GeminiLiveClient.MODEL_DIALOG,
                audioResponses = audioResponses,
                withTools = toolsOn,
                voice = currentVoice(),
                listener = liveListener
            )
        }
    }

    private val liveListener = object : GeminiLiveClient.Listener {
        override fun onStatus(text: String) {
            runOnUiThread {
                setStatus(text, if (text.startsWith("Live")) "#10B981" else "#F0ABFC")
                if (text.startsWith("Live")) btnConnect.text = "STOP"
            }
        }

        override fun onTextDelta(text: String) {
            // Text accumulates; shown on turn done (audio-first UX).
        }

        override fun onTurnDone(fullText: String) {
            runOnUiThread {
                if (fullText.isNotBlank()) modelRow(fullText)
                scroll()
            }
        }

        override fun onAudioChunk(pcm16: ByteArray) {
            playPcm(pcm16)
        }

        override fun onImage(mime: String, bytes: ByteArray) {
            runOnUiThread {
                imageRow(bytes, "Model image ($mime)")
                scroll()
            }
        }

        override fun onUserTranscript(text: String) {
            runOnUiThread {
                userRow("You (voice): $text")
                scroll()
            }
        }

        override fun onInterrupted() {
            stopPlayback()
            runOnUiThread { note("Interrupted.") }
        }

        override fun onToolCall(id: String, name: String, args: String) {
            runOnUiThread { toolRow("$name $args") }
            thread { executeTool(id, name, args) }
        }

        override fun onClosed(reason: String) {
            stopPlayback()
            runOnUiThread {
                setStatus("Disconnected", "#94A3B8")
                btnConnect.text = "CONNECT"
                note("Session closed: $reason")
                if (reason.contains("1007")) {
                    note("Hint 1007: server rejected a message. Reconnect and retry.")
                }
                if (reason.contains("1008") && reason.contains("not found", ignoreCase = true)) {
                    note("Hint: model ID not available for this key type. Check the model.")
                }
            }
        }

        override fun onError(err: String) {
            runOnUiThread {
                setStatus("Error", "#EF4444")
                btnConnect.text = "CONNECT"
                note("Error: $err")
                Toast.makeText(this@LivePlaygroundActivity, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setStatus(text: String, colorHex: String) {
        statusView.text = text
        try {
            statusView.setTextColor(Color.parseColor(colorHex))
        } catch (_: Exception) { }
    }

    // ── send ──────────────────────────────────────────────────────────────────

    private fun pickMedia() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Attach")
            .setItems(arrayOf("Image", "Video (frames)")) { _, which ->
                if (which == 0) imagePicker.launch("image/*")
                else videoPicker.launch("video/*")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendCurrent() {
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingImageB64 == null && pendingVideoFrames.isEmpty()) return
        if (!GeminiLiveClient.isConnected()) {
            Toast.makeText(this, "Tap CONNECT first", Toast.LENGTH_SHORT).show()
            return
        }
        // Debounce double-tap/Enter like the reference (rapid turns can 1007).
        val now = System.currentTimeMillis()
        if (now - lastSendAt < 400) return
        lastSendAt = now
        val img = pendingImageB64
        val frames = pendingVideoFrames
        pendingImageB64 = null
        pendingVideoFrames = emptyList()
        input.setText("")
        if (img != null) imageRow(decodeB64(img), "You (image)")
        if (frames.isNotEmpty()) note("You sent ${frames.size} video frames.")
        if (text.isNotBlank()) userRow(text)
        scroll()
        // Mixing an open mic stream with a text turn triggers server 1007: end it first.
        val wasMicLive = micStreaming
        thread {
            if (wasMicLive) {
                try {
                    GeminiLiveClient.sendAudioStreamEnd()
                } catch (_: Exception) { }
            }
            GeminiLiveClient.sendText(
                text.ifBlank {
                    when {
                        img != null -> "Describe this image."
                        frames.isNotEmpty() -> "Describe this video."
                        else -> ""
                    }
                },
                img,
                frames
            )
        }
    }

    private fun decodeB64(b64: String): ByteArray {
        return try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    // ── mic ───────────────────────────────────────────────────────────────────

    private fun toggleMic() {
        if (micStreaming) {
            stopMic()
            return
        }
        if (!GeminiLiveClient.isConnected()) {
            Toast.makeText(this, "Tap CONNECT first", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 701)
            return
        }
        startMic()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 701 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startMic()
        }
    }

    private fun startMic() {
        try {
            stopPlayback() // barge-in: our voice stops the model
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                GeminiLiveClient.IN_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                6400 * 4
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                note("Mic failed to initialize.")
                return
            }
            recorder = rec
            micStreaming = true
            btnMic.text = "MIC LIVE"
            btnMic.setTextColor(Color.parseColor("#EF4444"))
            note("Listening — tap MIC again to stop.")
            rec.startRecording()
            thread {
                val buf = ByteArray(6400)
                while (micStreaming) {
                    try {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            val chunk = buf.copyOf(n)
                            val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                            GeminiLiveClient.sendAudioChunk(b64)
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            note("Mic error: ${e.message}")
        }
    }

    private fun stopMic() {
        micStreaming = false
        try {
            recorder?.stop()
        } catch (_: Exception) { }
        try {
            recorder?.release()
        } catch (_: Exception) { }
        recorder = null
        runOnUiThread {
            btnMic.text = "MIC OFF"
            btnMic.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    // ── playback (inbuilt TTS) ────────────────────────────────────────────────

    @Synchronized
    private fun playPcm(pcm16: ByteArray) {
        try {
            var track = player
            if (track == null || track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                track?.release()
                val minBuf = AudioTrack.getMinBufferSize(
                    GeminiLiveClient.OUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(8192)
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    GeminiLiveClient.OUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4,
                    AudioTrack.MODE_STREAM
                )
                player = track
                track.play()
            }
            track.write(pcm16, 0, pcm16.size)
        } catch (e: Exception) {
            runOnUiThread { note("Playback error: ${e.message}") }
        }
    }

    private fun stopPlayback() {
        try {
            player?.pause()
            player?.flush()
        } catch (_: Exception) { }
    }

    // ── tools ─────────────────────────────────────────────────────────────────

    private fun executeTool(id: String, name: String, argsJson: String) {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            JSONObject()
        }
        val result = JSONObject()
        try {
            when (name) {
                "run_shell" -> {
                    val cmd = args.optString("command", "")
                    if (cmd.isBlank()) {
                        result.put("ok", false).put("note", "empty command")
                    } else {
                        val r = com.pr4nav.jarvis.Shell.termux(cmd, 30_000)
                        val out = (if (r.out.isNotBlank()) r.out else r.err).take(2000)
                        result.put("ok", (r.rc ?: -1) == 0)
                            .put("exit_code", r.rc ?: -1)
                            .put("output", out)
                    }
                }
                "read_file" -> {
                    val path = args.optString("path", "")
                    try {
                        result.put("ok", true).put("content", com.pr4nav.jarvis.Fs.read(path).take(4000))
                    } catch (e: Exception) {
                        result.put("ok", false).put("note", e.message ?: "read failed")
                    }
                }
                "get_time" -> {
                    result.put("ok", true).put(
                        "time",
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    )
                }
                "take_screenshot" -> {
                    val shot = captureScreenshot()
                    if (shot != null) {
                        runOnUiThread { imageRow(shot, "Screenshot for model") }
                        result.put("ok", true).put(
                            "note", "Screenshot captured and shown in chat."
                        )
                    } else {
                        result.put("ok", false)
                            .put("note", "Screenshot not permitted from this sandbox.")
                    }
                }
                else -> result.put("ok", false).put("note", "unknown tool: $name")
            }
        } catch (e: Exception) {
            try {
                result.put("ok", false).put("note", e.message ?: "failed")
            } catch (_: Exception) { }
        }
        runOnUiThread {
            note("Tool $name → ${result.optBoolean("ok", false)}")
            scroll()
        }
        GeminiLiveClient.respondTool(id, name, result)
    }

    /** Best-effort screencap; needs shell/root, else returns null. */
    private fun captureScreenshot(): ByteArray? {
        return try {
            val f = java.io.File(cacheDir, "live_shot_${System.currentTimeMillis()}.png")
            val r = com.pr4nav.jarvis.Shell.local("screencap -p ${f.absolutePath}", 10_000)
            if (r.rc == 0 && f.exists() && f.length() > 1000) {
                val bytes = f.readBytes()
                try {
                    f.delete()
                } catch (_: Exception) { }
                bytes
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // ── media helpers ─────────────────────────────────────────────────────────

    private fun jpegBase64(bmp: Bitmap, maxDim: Int, quality: Int): String {
        val scale = (maxDim.toFloat() / maxOf(bmp.width, bmp.height).toFloat()).coerceAtMost(1f)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
            )
        } else bmp
        val baos = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** Video file → up to 10 JPEG frames @1fps for the dialog model. */
    private fun extractVideoFrames(uriString: String): List<String> {
        val out = mutableListOf<String>()
        var mmr: MediaMetadataRetriever? = null
        try {
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, android.net.Uri.parse(uriString))
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val seconds = ((durMs / 1000).coerceAtMost(10)).coerceAtLeast(1)
            for (s in 0 until seconds.toInt()) {
                try {
                    val frame = mmr.getFrameAtTime(
                        s * 1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: continue
                    out.add(jpegBase64(frame, 768, 70))
                    try {
                        frame.recycle()
                    } catch (_: Exception) { }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        } finally {
            try {
                mmr?.release()
            } catch (_: Exception) { }
        }
        return out
    }

    // ── transcript rows ───────────────────────────────────────────────────────

    private fun scroll() {
        try {
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        } catch (_: Exception) { }
    }

    private fun note(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(this@LivePlaygroundActivity, 8) }
        }
        transcript.addView(tv)
        scroll()
    }

    private fun userRow(text: String) {
        transcript.addView(
            com.pr4nav.jarvis.chat.AgentBubbles.userBubble(this, text)
        )
        scroll()
    }

    private fun modelRow(text: String) {
        transcript.addView(
            com.pr4nav.jarvis.chat.AgentBubbles.agentCard(this, text, "Gemini Live", null)
        )
        scroll()
    }

    private fun toolRow(text: String) {
        val tv = TextView(this).apply {
            this.text = "[tool] $text"
            setTextColor(Color.parseColor("#6EE7B7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(this@LivePlaygroundActivity, 6) }
        }
        transcript.addView(tv)
        scroll()
    }

    private fun imageRow(bytes: ByteArray, caption: String) {
        try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_tool_card)
                val p = ChatUi.dp(this@LivePlaygroundActivity, 8)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = ChatUi.dp(this@LivePlaygroundActivity, 8) }
            }
            val cap = TextView(this).apply {
                text = caption
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ChatUi.dp(this@LivePlaygroundActivity, 6) }
            }
            box.addView(cap)
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ChatUi.dp(this@LivePlaygroundActivity, 220)
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            box.addView(iv)
            transcript.addView(box)
        } catch (_: Exception) { }
    }
}
