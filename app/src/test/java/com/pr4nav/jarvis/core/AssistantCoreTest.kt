package com.pr4nav.jarvis.core

import android.content.ContextWrapper
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AssistantCoreTest {

    @Before
    fun setup() {
        JarvisWorkspace.initWorkspace(null)
        CanonicalToolRegistry.init(null)
    }

    @Test
    fun testSubmitMessageLifecycleAndEvents() {
        val context = ContextWrapper(null)
        val request = AssistantMessageRequest(
            message = "Turn on Bluetooth",
            conversationId = "test_conv_1",
            streamTokens = false
        )

        val latch = CountDownLatch(1)
        val receivedEvents = mutableListOf<AssistantEvent>()
        var response: AssistantResponse? = null

        val reqId = AssistantCore.submitMessage(
            context = context,
            request = request,
            onEvent = { ev ->
                receivedEvents.add(ev)
            },
            onResult = { resp ->
                response = resp
                latch.countDown()
            }
        )

        assertNotNull("Request ID must not be null", reqId)
        assertTrue(reqId.isNotBlank())

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Request must complete within timeout", completed)
        assertNotNull("Response must be received", response)

        val resp = response!!
        assertEquals(reqId, resp.requestId)
        assertEquals("test_conv_1", resp.conversationId)
        assertTrue("Response text must be non-empty", resp.responseText.isNotBlank())
        assertTrue("Speech text must be non-empty", resp.speechText.isNotBlank())
        assertTrue("Must have emitted events", receivedEvents.isNotEmpty())
        assertTrue(receivedEvents.any { it.status == AssistantEventStatus.REQUEST_STARTED })
        assertTrue(receivedEvents.any { it.status == AssistantEventStatus.DONE })
    }

    @Test
    fun testCancelRequest() {
        val reqId = "test_cancel_123"
        AssistantCore.cancelRequest(reqId)
        // Verify cancellation executes without throwing
        assertTrue(true)
    }

    @Test
    fun testCapabilitiesAndEnvironmentDiscovery() {
        val caps = AssistantCore.getCapabilities()
        assertTrue("Capabilities list must be populated", caps.isNotEmpty())

        val env = AssistantCore.getEnvironment(null)
        assertEquals("Android", env.os)
        assertEquals("/storage/emulated/0/JARVIS/workspace", env.workspace)
    }
}
