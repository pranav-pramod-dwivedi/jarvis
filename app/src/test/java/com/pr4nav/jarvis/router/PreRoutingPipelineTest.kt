package com.pr4nav.jarvis.router

import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PreRoutingPipelineTest {

    @Before
    fun setup() {
        CanonicalToolRegistry.init(null)
    }

    @Test
    fun testExistingToolFirstBluetooth() {
        val decision = PreRoutingPipeline.filter(null, "Turn on Bluetooth")
        assertTrue("Must be DirectToolMatch", decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("system.bluetooth", match.toolName)
        assertEquals(true, match.arguments.optBoolean("state"))
    }

    @Test
    fun testExistingToolFirstFilesystemSearch() {
        val decision = PreRoutingPipeline.filter(null, "Find my downloaded PDF files")
        assertTrue("Must be DirectToolMatch", decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("search_files", match.toolName)
        assertEquals("pdf", match.arguments.optString("query"))
        assertEquals("/storage/emulated/0/Download", match.arguments.optString("path"))
    }

    @Test
    fun testDirectCapabilityDiscovery() {
        val decision = PreRoutingPipeline.filter(null, "What can you actually control on this phone?")
        assertTrue("Must be DirectToolMatch", decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("jarvis_environment", match.toolName)
        assertEquals("capabilities", match.arguments.optString("query"))
    }

    @Test
    fun testCodingTaskRequiresModelRoute() {
        val decision = PreRoutingPipeline.filter(null, "Make me a React app that tracks cricket scores")
        assertTrue("Must be ModelRoute", decision is PreRoutingDecision.ModelRoute)
        val modelRoute = decision as PreRoutingDecision.ModelRoute
        assertEquals(TaskCategory.CODING, modelRoute.classification.category)
    }

    @Test
    fun testSecurityGuardBlocksDestructiveCommands() {
        val decision = PreRoutingPipeline.filter(null, "rm -rf /storage/emulated/0")
        assertTrue("Must be Blocked", decision is PreRoutingDecision.Blocked)
    }
}
