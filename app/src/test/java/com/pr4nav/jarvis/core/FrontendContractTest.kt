package com.pr4nav.jarvis.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrontendContractTest {

    @Test
    fun testEventContractStructure() {
        val ev = AssistantEvent(
            requestId = "req-12345",
            status = AssistantEventStatus.EXECUTING,
            message = "Turning Bluetooth on…",
            metadata = JSONObject().put("tool", "system.bluetooth")
        )

        assertEquals("req-12345", ev.requestId)
        assertEquals(AssistantEventStatus.EXECUTING, ev.status)
        assertEquals("Turning Bluetooth on…", ev.message)
        assertEquals("system.bluetooth", ev.metadata?.optString("tool"))
    }

    @Test
    fun testResponseContractStructure() {
        val resp = AssistantResponse(
            requestId = "req-999",
            conversationId = "conv-abc",
            responseText = "Bluetooth has been enabled.",
            speechText = "Bluetooth has been enabled.",
            routeSelected = "DIRECT_EXISTING_TOOL",
            confidence = 0.99f,
            routeReason = "Exact capability match in Canonical Tool Registry",
            modelEngine = "JARVIS Direct Capability Layer",
            latencyMs = 12L,
            events = listOf(
                AssistantEvent("req-999", 1000L, AssistantEventStatus.REQUEST_STARTED, "Started"),
                AssistantEvent("req-999", 1012L, AssistantEventStatus.DONE, "Complete")
            )
        )

        assertEquals("req-999", resp.requestId)
        assertEquals("conv-abc", resp.conversationId)
        assertEquals("DIRECT_EXISTING_TOOL", resp.routeSelected)
        assertEquals(0.99f, resp.confidence, 0.001f)
        assertEquals(2, resp.events.size)
    }
}
