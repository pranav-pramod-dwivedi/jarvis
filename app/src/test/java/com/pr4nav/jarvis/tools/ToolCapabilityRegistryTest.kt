package com.pr4nav.jarvis.tools

import org.junit.Assert.*
import org.junit.Test

class ToolCapabilityRegistryTest {

    @Test
    fun testDomainsAndRegistration() {
        val all = ToolCapabilityRegistry.getAll()
        assertTrue("Must have multiple capabilities", all.size >= 12)

        val deviceCaps = ToolCapabilityRegistry.getByDomain(CapabilityDomain.DEVICE)
        assertTrue(deviceCaps.any { it.name == "system.bluetooth" })
        assertTrue(deviceCaps.any { it.name == "system.torch" })
        assertTrue(deviceCaps.any { it.name == "system.volume" })

        val filesCaps = ToolCapabilityRegistry.getByDomain(CapabilityDomain.FILES)
        assertTrue(filesCaps.any { it.name == "read_file" })
        assertTrue(filesCaps.any { it.name == "search_files" })

        val shellCaps = ToolCapabilityRegistry.getByDomain(CapabilityDomain.SHELL)
        assertTrue(shellCaps.any { it.name == "run_command" })

        val agentCaps = ToolCapabilityRegistry.getByDomain(CapabilityDomain.AGENT)
        assertTrue(agentCaps.any { it.name == "jarvis_environment" })
    }

    @Test
    fun testActionValidation() {
        // Valid actions
        assertTrue(ToolCapabilityRegistry.validateAction("system.bluetooth", "enable"))
        assertTrue(ToolCapabilityRegistry.validateAction("system.bluetooth", "disable"))
        assertTrue(ToolCapabilityRegistry.validateAction("system.torch", "enable"))
        assertTrue(ToolCapabilityRegistry.validateAction("system.volume", "raise"))

        // Invalid actions rejected
        assertFalse(ToolCapabilityRegistry.validateAction("system.bluetooth", "banana"))
        assertFalse(ToolCapabilityRegistry.validateAction("system.torch", "fly"))
        assertFalse(ToolCapabilityRegistry.validateAction("system.volume", "explode"))
    }

    @Test
    fun testCapabilitiesSummaryFormatting() {
        val summary = ToolCapabilityRegistry.getCapabilitiesSummary()

        assertTrue(summary.contains("JARVIS TOOL CAPABILITIES"))
        assertTrue(summary.contains("--- DEVICE"))
        assertTrue(summary.contains("--- FILES"))
        assertTrue(summary.contains("--- SHELL"))
        assertTrue(summary.contains("system.bluetooth"))
    }
}
