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
        val j = JSONObject(GeminiLiveClient.buildAudioChunk("AAAA"))
        val chunk = j.getJSONObject("realtimeInput").getJSONArray("mediaChunks").getJSONObject(0)
        assertEquals("audio/pcm;rate=16000", chunk.getString("mimeType"))
        assertEquals("AAAA", chunk.getString("data"))
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
        assertTrue(m.turnComplete)
        assertNull(m.audioPcm)
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

    @Test fun parseInterrupted() {
        val m = GeminiLiveClient.parseServerMessage("""{"serverContent":{"interrupted":true}}""")
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

    @Test fun liveModelIds() {
        assertTrue(GeminiLiveClient.LIVE_MODELS.all { it.startsWith("models/gemini-2.5-flash") })
        assertTrue(GeminiLiveClient.LIVE_MODELS.any { it.contains("native-audio-dialog") })
    }
}
