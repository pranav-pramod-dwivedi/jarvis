package com.pr4nav.jarvis.llm

import com.pr4nav.jarvis.CmdGuard
import com.pr4nav.jarvis.voice.KiraTtsClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KiraAutonomous1MAndTtsTest {

    @Test
    fun test1MContextCeilingAndPruning() {
        assertEquals("MAX_AGENT_TURNS should be 30 for deep autonomous chains", 30, KiraClient.MAX_AGENT_TURNS)
        assertEquals("Safe 1M context character ceiling should be 3.2M chars (~800k tokens)", 3_200_000, KiraClient.MAX_SAFE_CONTEXT_CHARS)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", "SYSTEM_PROMPT"))
        messages.put(JSONObject().put("role", "user").put("content", "A".repeat(100)))
        messages.put(JSONObject().put("role", "assistant").put("content", "B".repeat(100)))
        messages.put(JSONObject().put("role", "user").put("content", "LATEST_PROMPT"))

        // Pruning with maxChars = 150 should drop the earliest non-system user/assistant pair (indices 1)
        KiraClient.pruneContextSafely(messages, maxChars = 150)

        // System prompt (index 0) and latest prompt must ALWAYS be preserved
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("SYSTEM_PROMPT", messages.getJSONObject(0).getString("content"))
        val lastMsg = messages.getJSONObject(messages.length() - 1)
        assertEquals("user", lastMsg.getString("role"))
        assertEquals("LATEST_PROMPT", lastMsg.getString("content"))
    }

    @Test
    fun testMultiActionJsonParsing() {
        val dualActionResponse = """
            I am executing both commands autonomously in YOLO mode.
            ```json
            {"action": "execute_shell_command", "command": "getprop ro.product.model"}
            ```
            Now inspecting storage:
            ```json
            {"action": "execute_batch_commands", "commands": ["df -h", "ls -la /sdcard"]}
            ```
        """.trimIndent()

        val parsed = KiraClient.parseAllAgentActions(dualActionResponse)
        assertEquals("Should parse both JSON action blocks", 2, parsed.size)
        assertEquals("execute_shell_command", parsed[0].getString("action"))
        assertEquals("getprop ro.product.model", parsed[0].getString("command"))
        assertEquals("execute_batch_commands", parsed[1].getString("action"))
        assertEquals(2, parsed[1].getJSONArray("commands").length())
    }

    @Test
    fun testBatchShellCommandToolSchema() {
        val schema = KiraClient.buildJarvisToolsSchema()
        var hasBatchCommand = false
        for (i in 0 until schema.length()) {
            val fn = schema.getJSONObject(i).getJSONObject("function")
            if (fn.getString("name") == "execute_batch_commands") {
                hasBatchCommand = true
                val params = fn.getJSONObject("parameters")
                assertTrue(params.getJSONObject("properties").has("commands"))
            }
        }
        assertTrue("execute_batch_commands must be present in Jarvis tools schema", hasBatchCommand)
    }

    @Test
    fun testKiraWavHeaderGeneration() {
        // 24000 Hz, 16-bit mono PCM: 48000 bytes = 1.0 second
        val oneSecondPcm = ByteArray(48000)
        val wav = KiraTtsClient.addWavHeader(oneSecondPcm, sampleRate = 24000, channels = 1, bitsPerSample = 16)

        assertEquals("WAV size must be PCM size + 44 bytes", 48044, wav.size)

        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4)
        bb.get(riff)
        assertEquals("RIFF", String(riff, Charsets.US_ASCII))

        val totalLen = bb.int
        assertEquals(48044 - 8, totalLen)

        val wave = ByteArray(4)
        bb.get(wave)
        assertEquals("WAVE", String(wave, Charsets.US_ASCII))

        val fmt = ByteArray(4)
        bb.get(fmt)
        assertEquals("fmt ", String(fmt, Charsets.US_ASCII))

        val subchunk1Size = bb.int
        assertEquals(16, subchunk1Size)

        val audioFormat = bb.short
        assertEquals(1, audioFormat.toInt()) // 1 = PCM

        val numChannels = bb.short
        assertEquals(1, numChannels.toInt()) // 1 = Mono

        val sampleRate = bb.int
        assertEquals(24000, sampleRate)

        val byteRate = bb.int
        assertEquals(48000, byteRate) // 24000 * 1 * 16 / 8

        val blockAlign = bb.short
        assertEquals(2, blockAlign.toInt())

        val bitsPerSample = bb.short
        assertEquals(16, bitsPerSample.toInt())

        val dataId = ByteArray(4)
        bb.get(dataId)
        assertEquals("data", String(dataId, Charsets.US_ASCII))

        val dataLen = bb.int
        assertEquals(48000, dataLen)
    }

    @Test
    fun testYoloModeCmdGuardBypass() {
        CmdGuard.yoloMode = false
        assertNotNull("When yoloMode is false, destructive commands are blocked", CmdGuard.check("rm -rf /storage/emulated/0"))

        CmdGuard.yoloMode = true
        assertNull("When yoloMode is true (dangerously skip permissions), check returns null (allowed)", CmdGuard.check("rm -rf /storage/emulated/0"))
        assertNull(CmdGuard.check("reboot"))
        assertNull(CmdGuard.check("proot-distro remove ubuntu"))

        // Reset to false for test isolation
        CmdGuard.yoloMode = false
    }

    @Test
    fun testExtractThinkingNullSafety() {
        val (think1, clean1) = KiraClient.extractThinking("null")
        assertEquals("", think1)
        assertEquals("", clean1)

        val (think2, clean2) = KiraClient.extractThinking("<think>null</think>null")
        assertEquals("", think2)
        assertEquals("", clean2)

        val (think3, clean3) = KiraClient.extractThinking("<think>Analyzing battery\nChecking levels</think>battery is 90%")
        assertEquals("Analyzing battery\nChecking levels", think3)
        assertEquals("battery is 90%", clean3)
    }

    @Test
    fun testUserResponseSanitizerNullSafety() {
        val clean1 = com.pr4nav.jarvis.response.UserResponseSanitizer.cleanTextContent("null")
        assertFalse("cleanTextContent must never return literal null", clean1.equals("null", ignoreCase = true))

        val clean2 = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitize("null")
        assertFalse("sanitize must never return literal null", clean2.equals("null", ignoreCase = true))

        val speech1 = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitizeForSpeech("null")
        assertFalse("sanitizeForSpeech must never return literal Null", speech1.equals("null", ignoreCase = true))
        assertNotEquals("Null", speech1)
    }
}
