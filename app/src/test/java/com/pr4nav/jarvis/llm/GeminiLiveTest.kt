package com.pr4nav.jarvis.llm

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Brutal tests for Live API framing, base64, builders and parsers. */
class GeminiLiveTest {

    // ── WsBase64 ──────────────────────────────────────────────────────────────

    @Test fun base64RfcVectors() {
        assertEquals("", WsBase64.encode(ByteArray(0)))
        assertEquals("Zg==", WsBase64.encode("f".toByteArray()))
        assertEquals("Zm8=", WsBase64.encode("fo".toByteArray()))
        assertEquals("Zm9v", WsBase64.encode("foo".toByteArray()))
        assertEquals("Zm9vYmFy", WsBase64.encode("foobar".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), WsBase64.decode("Zm9vYmFy"))
        assertArrayEquals("f".toByteArray(), WsBase64.decode("Zg=="))
        assertArrayEquals(ByteArray(0), WsBase64.decode(""))
        assertArrayEquals(ByteArray(0), WsBase64.decode("!!!"))
    }

    @Test fun base64RoundTripBinary() {
        val rnd = java.util.Random(42)
        repeat(200) {
            val len = rnd.nextInt(300)
            val bytes = ByteArray(len).also { rnd.nextBytes(it) }
            assertArrayEquals(bytes, WsBase64.decode(WsBase64.encode(bytes)))
        }
    }

    @Test fun acceptKeyRfcVector() {
        // RFC 6455 §1.3 example.
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            LiveSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        )
    }

    // ── LiveSocket framing ────────────────────────────────────────────────────

    private fun roundTrip(payload: ByteArray, opcode: Int = 0x1, mask: Boolean): ByteArray {
        val encoded = LiveSocket.encodeFrame(opcode, payload, mask)
        val decoded = LiveSocket.decodeFrame(DataInputStream(ByteArrayInputStream(encoded)))!!
        assertEquals(opcode, decoded.opcode)
        assertTrue(decoded.fin)
        return decoded.payload
    }

    @Test fun frameRoundTrips() {
        assertArrayEquals("hi".toByteArray(), roundTrip("hi".toByteArray(), mask = true))
        assertArrayEquals("hi".toByteArray(), roundTrip("hi".toByteArray(), mask = false))
        val medium = ByteArray(1000) { it.toByte() }
        assertArrayEquals(medium, roundTrip(medium, mask = true))
        val large = ByteArray(100_000) { (it * 7).toByte() }
        assertArrayEquals(large, roundTrip(large, mask = true))
        assertArrayEquals(ByteArray(0), roundTrip(ByteArray(0), mask = true))
    }

    @Test fun maskedBitSetOnWire() {
        val encoded = LiveSocket.encodeFrame(0x1, "abc".toByteArray(), mask = true)
        assertEquals(0x81.toByte(), encoded[0])
        assertTrue((encoded[1].toInt() and 0x80) != 0)
        assertEquals(3, (encoded[1].toInt() and 0xFF) and 0x7F)
        // Payload must NOT appear literally (masked).
        assertFalse(String(encoded).contains("abc"))
    }

    @Test fun unmaskedServerFrameDecodes() {
        val encoded = LiveSocket.encodeFrame(0x1, "hello".toByteArray(), mask = false)
        val decoded = LiveSocket.decodeFrame(DataInputStream(ByteArrayInputStream(encoded)))!!
        assertEquals("hello", String(decoded.payload))
    }

    @Test fun closeFrameCodeParsed() {
        val payload = byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray()
        val decoded = LiveSocket.decodeFrame(
            DataInputStream(ByteArrayInputStream(LiveSocket.encodeFrame(0x8, payload, mask = false)))
        )!!
        assertEquals(0x8, decoded.opcode)
        assertEquals(1000, decoded.code)
    }

    @Test fun truncatedStreamReturnsNull() {
        assertNull(LiveSocket.decodeFrame(DataInputStream(ByteArrayInputStream(ByteArray(0)))))
        assertNull(LiveSocket.decodeFrame(DataInputStream(ByteArrayInputStream(byteArrayOf(0x81.toByte())))))
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    @Test fun setupHasVoiceAndTranscriptions() {
        val s = JSONObject(
            GeminiLiveClient.buildSetup(GeminiLiveClient.MODEL_DIALOG, true, "sys", null, voice = "Kore")
        ).getJSONObject("setup")
        assertEquals(
            "Kore",
            s.getJSONObject("generationConfig").getJSONObject("speechConfig")
                .getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig")
                .getString("voiceName")
        )
        assertTrue(s.has("inputAudioTranscription"))
        assertTrue(s.has("outputAudioTranscription"))
    }

    @Test fun setupAudioVsText() {
        val audio = JSONObject(GeminiLiveClient.buildSetup(GeminiLiveClient.MODEL_DIALOG, true, "sys", null))
        val mods = audio.getJSONObject("setup").getJSONObject("generationConfig").getJSONArray("responseModalities")
        assertEquals("AUDIO", mods.getString(0))
        assertEquals(
            "sys",
            audio.getJSONObject("setup").getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text")
        )

        val text = JSONObject(GeminiLiveClient.buildSetup(GeminiLiveClient.MODEL_DIALOG, false, "", null))
        assertEquals("TEXT", text.getJSONObject("setup").getJSONObject("generationConfig")
            .getJSONArray("responseModalities").getString(0))
        assertFalse(text.getJSONObject("setup").has("systemInstruction"))
        assertFalse(text.getJSONObject("setup").has("tools"))
    }

    @Test fun setupToolsShape() {
        val tools = GeminiLiveClient.buildTools()
        val withTools = JSONObject(
            GeminiLiveClient.buildSetup(GeminiLiveClient.MODEL_DIALOG, true, "", tools)
        )
        val decls = withTools.getJSONObject("setup").getJSONArray("tools")
            .getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }
        assertTrue(names.contains("run_shell"))
        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("get_time"))
        assertTrue(names.contains("take_screenshot"))
    }

    @Test fun textTurnWithAndWithoutImage() {
        val plain = JSONObject(GeminiLiveClient.buildTextTurn("hello"))
        val parts = plain.getJSONObject("clientContent").getJSONArray("turns")
            .getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals("hello", parts.getJSONObject(0).getString("text"))
        assertTrue(plain.getJSONObject("clientContent").getBoolean("turnComplete"))

        val img = JSONObject(GeminiLiveClient.buildTextTurn("see this", "QUJD"))
        val parts2 = img.getJSONObject("clientContent").getJSONArray("turns")
            .getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts2.length())
        assertEquals("image/jpeg", parts2.getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("QUJD", parts2.getJSONObject(0).getJSONObject("inlineData").getString("data"))
    }

    @Test fun textTurnWithVideoFrames() {
        val j = JSONObject(
            GeminiLiveClient.buildTextTurn("describe", null, listOf("FR1", "FR2"))
        )
        val parts = j.getJSONObject("clientContent").getJSONArray("turns")
            .getJSONObject(0).getJSONArray("parts")
        // frame, marker, frame, text
        assertEquals(4, parts.length())
        assertEquals("FR1", parts.getJSONObject(0).getJSONObject("inlineData").getString("data"))
        assertEquals("FR2", parts.getJSONObject(2).getJSONObject("inlineData").getString("data"))
        assertEquals("describe", parts.getJSONObject(3).getString("text"))
    }

    @Test fun audioChunkShape() {
        // Reference shape: realtimeInput.audio (NOT mediaChunks).
        val j = JSONObject(GeminiLiveClient.buildAudioChunk("AAAA"))
        val chunk = j.getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", chunk.getString("mimeType"))
        assertEquals("AAAA", chunk.getString("data"))
    }

    @Test fun streamEndAndVideoShapes() {
        val end = JSONObject(GeminiLiveClient.buildAudioStreamEnd())
        assertTrue(end.getJSONObject("realtimeInput").getBoolean("audioStreamEnd"))
        val vid = JSONObject(GeminiLiveClient.buildVideoChunk("VVVV"))
        val chunk = vid.getJSONObject("realtimeInput").getJSONObject("video")
        assertEquals("image/jpeg", chunk.getString("mimeType"))
        assertEquals("VVVV", chunk.getString("data"))
    }

    @Test fun toolResponseShape() {
        val j = JSONObject(GeminiLiveClient.buildToolResponse("c1", "run_shell", JSONObject().put("output", "ok")))
        val fr = j.getJSONObject("toolCallResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", fr.getString("id"))
        assertEquals("run_shell", fr.getString("name"))
        assertEquals("ok", fr.getJSONObject("response").getJSONObject("result").getString("output"))
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    @Test fun parseSetupComplete() {
        val m = GeminiLiveClient.parseServerMessage("""{"setupComplete":{}}""")
        assertTrue(m.setupComplete)
    }

    @Test fun parseTextTurn() {
        val m = GeminiLiveClient.parseServerMessage(
            """{"serverContent":{"modelTurn":{"parts":[{"text":"Hel"},{"text":"lo"}]},"turnComplete":true}}"""
        )
        assertEquals("Hello", m.textDelta)
        assertEquals("", m.thoughtDelta)
        assertTrue(m.turnComplete)
        assertNull(m.audioPcm)
    }

    @Test fun thoughtPartsStayOutOfAnswer() {
        val m = GeminiLiveClient.parseServerMessage(
            """{"serverContent":{"modelTurn":{"parts":[{"text":"Let me think","thought":true},{"text":"Here you go"}]},"turnComplete":true}}"""
        )
        assertEquals("Here you go", m.textDelta)
        assertEquals("Let me think", m.thoughtDelta)
        assertTrue(m.turnComplete)
    }

    @Test fun parseAudioChunk() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val b64 = WsBase64.encode(pcm)
        val m = GeminiLiveClient.parseServerMessage(
            "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"$b64\"}}]}}}"
        )
        assertArrayEquals(pcm, m.audioPcm)
        assertTrue(m.images.isEmpty())
        assertEquals("", m.textDelta)
    }

    @Test fun parseAgentImageRoutedToImages() {
        val img = byteArrayOf(10, 20, 30)
        val b64 = WsBase64.encode(img)
        val m = GeminiLiveClient.parseServerMessage(
            "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/jpeg\",\"data\":\"$b64\"}}]}}}"
        )
        assertNull(m.audioPcm)
        assertEquals(1, m.images.size)
        assertEquals("image/jpeg", m.images[0].first)
        assertArrayEquals(img, m.images[0].second)
    }

    @Test fun parseTranscriptionsAndGoAway() {
        val m = GeminiLiveClient.parseServerMessage(
            """{"serverContent":{"inputTranscription":{"text":"hello there"},"outputTranscription":{"text":"Hi human"},"turnComplete":true}}"""
        )
        assertEquals("hello there", m.inputTranscript)
        assertEquals("Hi human", m.outputTranscript)
        assertTrue(m.turnComplete)
        val g = GeminiLiveClient.parseServerMessage("""{"goAway":{}}""")
        assertTrue(g.goAway)
    }

    @Test fun pickFinalAnswerPrefersWritten() {
        // Server sends both channels for the same words: never concatenate.
        assertEquals(
            "Here you go",
            GeminiLiveClient.pickFinalAnswer("Here you go", "Here you go")
        )
        assertEquals("Written", GeminiLiveClient.pickFinalAnswer("Spoken", "Written"))
        assertEquals("Spoken", GeminiLiveClient.pickFinalAnswer("Spoken", ""))
        assertEquals("Spoken", GeminiLiveClient.pickFinalAnswer("Spoken", "   "))
        assertEquals("", GeminiLiveClient.pickFinalAnswer("", ""))
    }

    @Test fun parseInterrupted() {        val m = GeminiLiveClient.parseServerMessage("""{"serverContent":{"interrupted":true}}""")
        assertTrue(m.interrupted)
    }

    @Test fun parseToolCall() {
        val m = GeminiLiveClient.parseServerMessage(
            """{"toolCall":{"functionCalls":[{"id":"a1","name":"run_shell","args":{"command":"ls"}}]}}"""
        )
        assertEquals(1, m.toolCalls.size)
        assertEquals("a1", m.toolCalls[0].id)
        assertEquals("run_shell", m.toolCalls[0].name)
        assertTrue(m.toolCalls[0].args.contains("ls"))
    }

    @Test fun parseError() {
        val m = GeminiLiveClient.parseServerMessage("""{"error":{"code":400,"message":"bad model"}}""")
        assertTrue(m.error.contains("400"))
        assertTrue(m.error.contains("bad model"))
    }

    @Test fun parseHostile() {
        listOf("", "   ", "not json", "{}", "[]", "null", """{"serverContent":null}""",
            """{"serverContent":{"modelTurn":null}}""", """{"toolCall":{}}""").forEach { s ->
            val m = GeminiLiveClient.parseServerMessage(s)
            assertEquals("", m.textDelta)
            assertNull(m.audioPcm)
            assertTrue(m.toolCalls.isEmpty())
        }
    }

    @Test fun liveModelIds() {        // Reference-verified IDs (AI Studio Live playground).
        assertTrue(GeminiLiveClient.LIVE_MODELS.contains(GeminiLiveClient.MODEL_DIALOG))
        assertEquals(
            "models/gemini-2.5-flash-native-audio-preview-09-2025",
            GeminiLiveClient.MODEL_DIALOG
        )
        assertTrue(GeminiLiveClient.VOICES.contains("Aoede"))
        assertTrue(GeminiLiveClient.VOICES.contains("Autonoe"))
        assertTrue(GeminiLiveClient.VOICES.contains("Kore"))
    }

    @Test fun buildUrlEncodesKey() {
        val url = LiveSocket().buildUrl("h", "/p", "a+b/c=d e")
        assertTrue(url.startsWith("wss://h/p?key="))
        assertFalse(url.contains("+b"))
        assertTrue(url.contains("a%2Bb%2Fc%3Dd+e") || url.contains("a%2Bb%2Fc%3Dd%20e"))
    }

    @Test fun concurrentFramesStaySequential() {
        // Proves the write-lock invariant: concatenated frames always re-parse in order.
        val payloads = (0 until 60).map { "payload-$it-" + "x".repeat(it % 7 * 50) }
        val results = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val threads = payloads.map { p ->
            Thread { results.add(LiveSocket.encodeTextFrame(p)) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        assertEquals(60, results.size)
        val seen = mutableSetOf<String>()
        for (bytes in results) {
            val all = bytes
            var pos = 0
            while (pos < all.size) {
                val din = java.io.DataInputStream(java.io.ByteArrayInputStream(all, pos, all.size - pos))
                val f = LiveSocket.decodeFrame(din) ?: break
                // Each encodeTextFrame emits exactly one frame; decode it fully.
                seen.add(String(f.payload))
                pos = all.size
            }
        }
        assertEquals(payloads.toSet(), seen)
    }

    @Test fun binaryFramePayloadIntact() {
        val json = """{"setupComplete":{}}"""
        val bytes = LiveSocket.encodeFrame(0x2, json.toByteArray(), mask = false)
        val f = LiveSocket.decodeFrame(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)))!!
        assertEquals(0x2, f.opcode)
        assertEquals(json, String(f.payload))
    }

    @Test fun lastSentReportDefault() {
        assertTrue(GeminiLiveClient.lastSentReport().startsWith("none"))
    }
}
