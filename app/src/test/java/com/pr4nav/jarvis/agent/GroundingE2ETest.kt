package com.pr4nav.jarvis.agent

import com.pr4nav.jarvis.router.JarvisRouter
import com.pr4nav.jarvis.router.PreRoutingDecision
import com.pr4nav.jarvis.router.PreRoutingPipeline
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolCapabilityRegistry
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GroundingE2ETest {

    @Before
    fun setup() {
        JarvisWorkspace.initWorkspace()
        CanonicalToolRegistry.init(android.content.ContextWrapper(null))
    }

    @Test
    fun testScenario1_CalculatorAppWorkspaceAnchoring() {
        // "Make me a calculator app" -> Project in /storage/emulated/0/JARVIS/workspace/calculator/
        val res = AgentActionLoop.buildVerifiedProject(
            context = null,
            projectName = "calculator",
            files = mapOf(
                "calc.py" to "def calculate(op, a, b):\n    return a + b\n",
                "calc_test.py" to "from calc import calculate\nassert calculate('+', 2, 2) == 4\n"
            )
        )

        assertEquals("/storage/emulated/0/JARVIS/workspace/calculator", res.projectPath)
        assertTrue(res.projectPath.startsWith(JarvisWorkspace.WORKSPACE_DIR))
        assertFalse("Must never create project in /root", res.projectPath.startsWith("/root"))
        assertEquals(2, res.filesCreated.size)
    }

    @Test
    fun testScenario2_WhatCanYouActuallyControl() {
        // "What can you actually control on this phone?"
        val decision = PreRoutingPipeline.filter(null, "What can you actually control on this phone?")
        assertTrue("Must resolve directly to jarvis_environment", decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("jarvis_environment", match.toolName)

        val summary = ToolCapabilityRegistry.getCapabilitiesSummary()
        assertTrue(summary.contains("system.bluetooth"))
        assertTrue(summary.contains("system.torch"))
        assertTrue(summary.contains("system.volume"))
        assertTrue(summary.contains("DEVICE"))
        assertTrue(summary.contains("FILES"))
    }

    @Test
    fun testScenario3_TurnBluetoothOn() {
        // "Turn Bluetooth on"
        val decision = PreRoutingPipeline.filter(null, "Turn Bluetooth on")
        assertTrue(decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("system.bluetooth", match.toolName)
        assertEquals(true, match.arguments.optBoolean("state"))
    }

    @Test
    fun testScenario4_FindDownloadedPdfFiles() {
        // "Find my downloaded PDF files"
        val decision = PreRoutingPipeline.filter(null, "Find my downloaded PDF files")
        assertTrue(decision is PreRoutingDecision.DirectToolMatch)
        val match = decision as PreRoutingDecision.DirectToolMatch
        assertEquals("search_files", match.toolName)
        assertEquals("pdf", match.arguments.optString("query"))
        assertEquals("/storage/emulated/0/Download", match.arguments.optString("path"))
    }

    @Test
    fun testScenario5_BuildSmallPythonProgramAndVerify() {
        // "Build a small Python program and run it"
        val res = AgentActionLoop.buildVerifiedProject(
            context = null,
            projectName = "hello_python",
            files = mapOf("main.py" to "print('JARVIS Execution Verified')\n")
        )

        assertTrue(res.projectPath.startsWith(JarvisWorkspace.WORKSPACE_DIR))
        assertEquals(1, res.filesCreated.size)
        assertTrue(res.stepLogs.any { it.phase == "OBSERVE" })
        assertTrue(res.stepLogs.any { it.phase == "ACT" })
        assertTrue(res.stepLogs.any { it.phase == "VERIFY" })
    }
}
