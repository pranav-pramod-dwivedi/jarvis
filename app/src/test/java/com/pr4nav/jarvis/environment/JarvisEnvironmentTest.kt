package com.pr4nav.jarvis.environment

import org.junit.Assert.*
import org.junit.Test

class JarvisEnvironmentTest {

    @Test
    fun testSnapshotCapturesRealProperties() {
        val snap = JarvisEnvironment.getSnapshot()

        assertEquals("Android", snap.os)
        assertNotNull(snap.device)
        assertTrue(snap.androidApi > 0)
        assertNotNull(snap.architecture)
        assertEquals("/storage/emulated/0/JARVIS/workspace", snap.workspace)
        assertTrue(snap.canonicalToolsCount > 0)

        val report = snap.toFormattedReport()
        assertTrue(report.contains("OS: Android"))
        assertTrue(report.contains("JARVIS workspace: /storage/emulated/0/JARVIS/workspace"))
        assertTrue(report.contains("Canonical tools:"))

        val json = snap.toJson()
        assertEquals("Android", json.optString("os"))
        assertEquals("/storage/emulated/0/JARVIS/workspace", json.optString("workspace"))
    }

    @Test
    fun testAgentContextHeaderGeneration() {
        val header = JarvisEnvironment.getAgentContextHeader()

        assertTrue(header.contains("JARVIS ENVIRONMENT"))
        assertTrue(header.contains("You are an internal agent of JARVIS."))
        assertTrue(header.contains("Workspace:"))
        assertTrue(header.contains("/storage/emulated/0/JARVIS/workspace"))
        assertTrue(header.contains("Available execution:"))
        assertTrue(header.contains("Needle:"))
        assertTrue(header.contains("Never invent tool results."))
        assertTrue(header.contains("Never claim success without verification."))
    }
}
