package com.pr4nav.jarvis.core

import android.content.ContextWrapper
import com.pr4nav.jarvis.agent.AgentActionLoop
import com.pr4nav.jarvis.context.CandidateItem
import com.pr4nav.jarvis.context.ContextManager
import com.pr4nav.jarvis.router.PreRoutingDecision
import com.pr4nav.jarvis.router.PreRoutingPipeline
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SiriReadyIntegrationTest {

    @Before
    fun setup() {
        JarvisWorkspace.initWorkspace(null)
        CanonicalToolRegistry.init(null)
        ContextManager.clear()
    }

    @Test
    fun testTurn1_DirectBluetoothOn() {
        val decision = PreRoutingPipeline.filter(null, "Turn Bluetooth on")
        assertTrue(decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("system.bluetooth", match.toolName)
        assertEquals(true, match.arguments.optBoolean("state"))
    }

    @Test
    fun testTurn2_ContextStateToggleOff() {
        // Setup initial tool context
        ContextManager.updateToolContext("system.bluetooth", org.json.JSONObject(), "bluetooth")

        // Follow up: "Turn it off"
        val decision = PreRoutingPipeline.filter(null, "Turn it off")
        assertTrue(decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("system.bluetooth", match.toolName)
        assertEquals(false, match.arguments.optBoolean("state"))
        assertTrue(match.reason.contains("Context Continuation"))
    }

    @Test
    fun testTurn3_FilesystemSearch() {
        val decision = PreRoutingPipeline.filter(null, "Find my downloaded PDF files")
        assertTrue(decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("search_files", match.toolName)
        assertEquals("pdf", match.arguments.optString("query"))
        assertEquals("/storage/emulated/0/Download", match.arguments.optString("path"))
    }

    @Test
    fun testTurn4_CapabilityRegistryDiscovery() {
        val decision = PreRoutingPipeline.filter(null, "What can you control on this phone?")
        assertTrue(decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("jarvis_environment", match.toolName)
        assertEquals("capabilities", match.arguments.optString("query"))
    }

    @Test
    fun testTurn5_ContactDisambiguationSelection() {
        val candidates = listOf(
            CandidateItem(1, "Akhil Mobile", "+919876543210"),
            CandidateItem(2, "Akhil Work", "+919123456780")
        )
        ContextManager.setCandidateList("call_contact", candidates)

        val decision = PreRoutingPipeline.filter(null, "the first one")
        assertTrue(decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("call_contact", match.toolName)
        assertEquals("+919876543210", match.arguments.optString("number"))
    }

    @Test
    fun testTurn6_CalculatorAppCreationAndVerification() {
        val res = AgentActionLoop.buildVerifiedProject(
            context = null,
            projectName = "calculator",
            files = mapOf("calc.py" to "print('Calculator Ready')\n")
        )

        assertEquals("/storage/emulated/0/JARVIS/workspace/calculator", res.projectPath)
        assertTrue(res.verified)
        assertTrue(res.success)
    }

    @Test
    fun testAssistantCoreFullContractLifecycle() {
        val context = ContextWrapper(null)
        val request = AssistantMessageRequest(
            message = "Find my downloaded PDF files",
            conversationId = "siri_ready_session"
        )

        val latch = CountDownLatch(1)
        var response: AssistantResponse? = null

        AssistantCore.submitMessage(
            context = context,
            request = request,
            onEvent = {},
            onResult = { resp ->
                response = resp
                latch.countDown()
            }
        )

        latch.await(5, TimeUnit.SECONDS)
        assertNotNull(response)
        assertEquals("siri_ready_session", response!!.conversationId)
        assertTrue(response!!.responseText.isNotBlank())
    }
}
