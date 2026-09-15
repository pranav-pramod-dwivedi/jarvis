package com.pr4nav.jarvis.llm

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Live tools + conversation turns (no network). */
class LiveContextToolsTest {

    @Test fun getTimeWorks() {
        val r = LiveToolExecutor.execute("get_time", "{}", null)
        assertTrue(r.optBoolean("ok", false))
        assertTrue(r.optString("time", "").length >= 10)
    }

    @Test fun unknownToolFailsClean() {
        val r = LiveToolExecutor.execute("teleport", "{}", null)
        assertFalse(r.optBoolean("ok", true))
        assertTrue(r.optString("note", "").contains("teleport"))
    }

    @Test fun emptyArgsFailClean() {
        assertFalse(LiveToolExecutor.execute("run_shell", "{}", null).optBoolean("ok", true))
        assertFalse(LiveToolExecutor.execute("read_file", "{\"path\":\"\"}", null).optBoolean("ok", true))
        assertFalse(LiveToolExecutor.execute("run_shell", "not json", null).optBoolean("ok", true))
    }

    @Test fun readMissingFileFailsClean() {
        val r = LiveToolExecutor.execute("read_file", "{\"path\":\"/nope/missing.txt\"}", null)
        assertFalse(r.optBoolean("ok", true))
    }

    @Test fun screenshotWithoutContextIsSafe() {
        // Must never throw, even with null context.
        val r = LiveToolExecutor.execute("take_screenshot", "{}", null)
        assertTrue(r.has("ok"))
    }

    @Test fun conversationTurnMapsRoles() {
        val j = JSONObject(
            GeminiLiveClient.buildConversationTurn(
                listOf("user" to "hi", "assistant" to "hello back", "user" to "   "),
                "how are you",
                null,
                emptyList()
            )
        )
        val turns = j.getJSONObject("clientContent").getJSONArray("turns")
        assertEquals(3, turns.length())
        assertEquals("user", turns.getJSONObject(0).getString("role"))
        assertEquals("model", turns.getJSONObject(1).getString("role"))
        assertEquals("how are you", turns.getJSONObject(2).getJSONArray("parts")
            .getJSONObject(0).getString("text"))
        assertTrue(j.getJSONObject("clientContent").getBoolean("turnComplete"))
    }

    @Test fun historyCappedAndTrimmed() {
        val big = (1..30).map { "user" to "question number $it with padding words here yes" }
        val j = JSONObject(GeminiLiveClient.buildConversationTurn(big, "now?", null, emptyList()))
        val turns = j.getJSONObject("clientContent").getJSONArray("turns")
        // 12 history + 1 current.
        assertEquals(13, turns.length())
        // Oldest dropped: first history turn is question 19.
        val firstText = turns.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(firstText.contains("19"))
    }

    @Test fun longHistoryLinesTruncated() {
        val long = "x".repeat(5000)
        val j = JSONObject(GeminiLiveClient.buildConversationTurn(listOf("user" to long), "q?", null, emptyList()))
        val turns = j.getJSONObject("clientContent").getJSONArray("turns")
        val t = turns.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(t.length <= 800)
    }
}
