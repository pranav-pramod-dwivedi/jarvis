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

    @Test fun deviceToolBranchNeverThrows() {
        // Registry needs a real device; the contract is no-throw + ok flag.
        val r1 = LiveToolExecutor.execute("execute_device_tool", "{}", null)
        assertFalse(r1.optBoolean("ok", true))
        val r2 = LiveToolExecutor.execute(
            "execute_device_tool", "{\"tool_name\":\"no_such_tool_xyz\",\"parameters\":{}}", null
        )
        assertTrue(r2.has("ok"))
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

@Test fun conversationTurnIsSingleTurnWithInlineContext() {
    val j = JSONObject(
        GeminiLiveClient.buildConversationTurn(
            listOf("user" to "hi", "assistant" to "hello back", "user" to "   "),
            "how are you",
            null,
            emptyList()
        )
    )
    val turns = j.getJSONObject("clientContent").getJSONArray("turns")
    // Exactly ONE turn (multi-turn arrays get server 1007s).
    assertEquals(1, turns.length())
    assertEquals("user", turns.getJSONObject(0).getString("role"))
    val full = turns.getJSONObject(0).getJSONArray("parts")
        .getJSONObject(0).getString("text")
    assertTrue(full.contains("[Conversation so far]"))
    assertTrue(full.contains("User: hi"))
    assertTrue(full.contains("Assistant: hello back"))
    assertTrue(full.endsWith("how are you"))
    assertTrue(j.getJSONObject("clientContent").getBoolean("turnComplete"))
}

@Test fun emptyHistoryIsBarePrompt() {
    val j = JSONObject(GeminiLiveClient.buildConversationTurn(emptyList(), "yo?", null, emptyList()))
    val turns = j.getJSONObject("clientContent").getJSONArray("turns")
    assertEquals(1, turns.length())
    assertEquals(
        "yo?",
        turns.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
    )
}

    @Test fun historyBudgetPrefersFresh() {
        // 40 long items overflow the ~8k budget: freshest kept, oldest dropped.
        val big = (1..40).map { "user" to ("question number $it " + "with padding words here yes indeed ".repeat(6)) }
        val j = JSONObject(GeminiLiveClient.buildConversationTurn(big, "now?", null, emptyList()))
        val turns = j.getJSONObject("clientContent").getJSONArray("turns")
        assertEquals(1, turns.length())
        val full = turns.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(full.contains("40"))
        assertFalse(full.contains("question number 1 "))
        assertTrue(full.endsWith("now?"))
        assertTrue(full.length <= 9500)
    }

@Test fun longHistoryLinesTruncated() {
    val long = "x".repeat(5000)
    val j = JSONObject(GeminiLiveClient.buildConversationTurn(listOf("user" to long), "q?", null, emptyList()))
    val full = j.getJSONObject("clientContent").getJSONArray("turns")
        .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
    assertTrue(full.length < 3500)
    assertTrue(full.endsWith("q?"))
}
}