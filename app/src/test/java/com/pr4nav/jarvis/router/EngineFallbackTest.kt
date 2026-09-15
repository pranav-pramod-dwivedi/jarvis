package com.pr4nav.jarvis.router

import com.pr4nav.jarvis.llm.KiraClient
import org.junit.Assert.*
import org.junit.Test

/** Timeout breaker tiers + the unconditional offline floor. */
class EngineFallbackTest {

    @Test fun tieredDeadlines() {
        assertEquals(25L, KiraClient.deadlineFor(KiraClient.MODEL_KIRA_MINI))
        assertEquals(40L, KiraClient.deadlineFor(KiraClient.MODEL_QWEN_3_8_FLASH_FREE))
        assertEquals(40L, KiraClient.deadlineFor(KiraClient.MODEL_MIMO_2_5_FREE))
        assertEquals(40L, KiraClient.deadlineFor(KiraClient.MODEL_KIRA_3_5_FLASH))
        assertEquals(120L, KiraClient.deadlineFor(KiraClient.MODEL_GLM_5_3_FREE))
        assertEquals(120L, KiraClient.deadlineFor(KiraClient.MODEL_KIRA_3_5_PRO))
        assertEquals(120L, KiraClient.deadlineFor("some-unknown-model"))
    }

    @Test fun timeoutDetection() {
        // Android's SocketTimeoutException message is exactly "timeout".
        assertTrue(KiraClient.isTimeoutError("timeout"))
        assertTrue(KiraClient.isTimeoutError("Read timed out"))
        assertTrue(KiraClient.isTimeoutError("Model glm timed out after 90s"))
        assertTrue(KiraClient.isTimeoutError("java.net.SocketTimeoutException: timeout"))
        assertTrue(KiraClient.isTimeoutError("Socket closed"))
        assertTrue(KiraClient.isTimeoutError("connect timed out"))
        assertFalse(KiraClient.isTimeoutError("HTTP 402: insufficient"))
        assertFalse(KiraClient.isTimeoutError("HTTP 500: server error"))
        assertFalse(KiraClient.isTimeoutError(""))
        assertFalse(KiraClient.isTimeoutError(null))
    }

    @Test fun quotaAndTimeoutAreDisjoint() {
        // A quota error must never be mistaken for a stall (and vice versa).
        val quota = "HTTP 402: insufficient VND wallet balance"
        assertTrue(KiraClient.isQuotaError(quota))
        assertFalse(KiraClient.isTimeoutError(quota))
        assertFalse(KiraClient.isQuotaError("timeout"))
    }

    @Test fun effectiveRouteAlwaysEndsLocal() {
        val route = UnifiedAssistantDispatcher.effectiveRoute(null)
        assertTrue(route.contains(RouteEngine.KIRA))
        assertTrue(route.contains(RouteEngine.GROQ))
        assertTrue(route.contains(RouteEngine.GEMINI))
        assertEquals(RouteEngine.LOCAL, route.last())
    }

    @Test fun routeShortCoversLocal() {
        assertEquals("Local solo",
            UnifiedAssistantDispatcher.routeShort(listOf(RouteEngine.LOCAL)))
        assertTrue(
            UnifiedAssistantDispatcher.routeShort(
                listOf(RouteEngine.KIRA, RouteEngine.LOCAL)
            ).contains("L")
        )
    }

    @Test fun nearestPlatformId() {
        val ids = listOf("glm-4.5-flash", "qwen3-235b-a22b", "kira-mini-1.0", "mimo-v2-flash")
        assertEquals("glm-4.5-flash", KiraClient.nearestPlatformId("glm-5.3-free", ids))
        assertEquals("kira-mini-1.0", KiraClient.nearestPlatformId("kira-mini-9.9", ids))
        assertNull(KiraClient.nearestPlatformId("zzz-top-999", ids))
        assertNull(KiraClient.nearestPlatformId("", ids))
        assertNull(KiraClient.nearestPlatformId("glm", emptyList()))
    }

    @Test fun localEngineAnswersConversationallyWithNoRuntime() {
        val ctx = android.content.ContextWrapper(null)
        val events = mutableListOf<com.pr4nav.jarvis.chat.AgentStreamEvent>()
        val t0 = System.currentTimeMillis()
        val res = LocalEngine.run(ctx, "hi", t0) { events.add(it) }
        assertTrue(res.handled)
        assertTrue(res.fullSummary.isNotBlank())
        assertTrue(res.fullSummary.contains("offline", ignoreCase = true))
        assertTrue(events.any { it is com.pr4nav.jarvis.chat.AgentStreamEvent.Final })
        assertTrue(System.currentTimeMillis() - t0 < 15_000)
    }
}
