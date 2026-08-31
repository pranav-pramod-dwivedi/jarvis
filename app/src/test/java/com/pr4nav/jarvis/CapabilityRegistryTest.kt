package com.pr4nav.jarvis

import com.pr4nav.jarvis.registry.CapabilityRegistry
import com.pr4nav.jarvis.registry.RiskLevel
import org.junit.Assert.*
import org.junit.Test

class CapabilityRegistryTest {

    @Test
    fun testRegistryInitialization() {
        val total = CapabilityRegistry.size()
        assertTrue("Capability registry should contain over 40 capabilities, found: $total", total >= 40)
    }

    @Test
    fun testDirectCapabilityLookup() {
        assertNotNull(CapabilityRegistry.get("system.battery"))
        assertNotNull(CapabilityRegistry.get("system.torch.on"))
        assertNotNull(CapabilityRegistry.get("system.torch.off"))
        assertNotNull(CapabilityRegistry.get("system.volume.set"))
        assertNotNull(CapabilityRegistry.get("media.play"))
        assertNotNull(CapabilityRegistry.get("media.spotify.open"))
        assertNotNull(CapabilityRegistry.get("file.list"))
        assertNotNull(CapabilityRegistry.get("termux.execute"))
        assertNotNull(CapabilityRegistry.get("clock.alarm.set"))
        assertNotNull(CapabilityRegistry.get("clock.timer.set"))
        assertNotNull(CapabilityRegistry.get("gui.open.files"))
        assertNotNull(CapabilityRegistry.get("gui.open.terminal"))
    }

    @Test
    fun testFlashlightAliasMatching() {
        val m1 = CapabilityRegistry.match("turn on flashlight")
        assertNotNull(m1)
        assertEquals("system.torch.on", m1!!.capability.id)

        val m2 = CapabilityRegistry.match("turn off flashlight")
        assertNotNull(m2)
        assertEquals("system.torch.off", m2!!.capability.id)

        val m3 = CapabilityRegistry.match("phone light on")
        assertNotNull(m3)
        assertEquals("system.torch.on", m3!!.capability.id)
    }

    @Test
    fun testBatteryAliasMatching() {
        val m1 = CapabilityRegistry.match("what is my battery")
        assertNotNull(m1)
        assertEquals("system.battery", m1!!.capability.id)

        val m2 = CapabilityRegistry.match("check battery")
        assertNotNull(m2)
        assertEquals("system.battery", m2!!.capability.id)
    }

    @Test
    fun testVolumeParameterExtraction() {
        val m = CapabilityRegistry.match("set volume to 80%")
        assertNotNull(m)
        assertEquals("system.volume.set", m!!.capability.id)
        assertEquals(80, m.params["value"])

        val m2 = CapabilityRegistry.match("volume 5")
        assertNotNull(m2)
        assertEquals("system.volume.set", m2!!.capability.id)
        assertEquals(5, m2.params["value"])
    }

    @Test
    fun testAlarmParameterExtraction() {
        val m1 = CapabilityRegistry.match("set an alarm for 7:30 AM")
        assertNotNull(m1)
        assertEquals("clock.alarm.set", m1!!.capability.id)
        assertEquals(7, m1.params["hour"])
        assertEquals(30, m1.params["minute"])

        val m2 = CapabilityRegistry.match("set alarm for 6 PM")
        assertNotNull(m2)
        assertEquals("clock.alarm.set", m2!!.capability.id)
        assertEquals(18, m2.params["hour"])
        assertEquals(0, m2.params["minute"])
    }

    @Test
    fun testTimerParameterExtraction() {
        val m1 = CapabilityRegistry.match("set a timer for 10 minutes")
        assertNotNull(m1)
        assertEquals("clock.timer.set", m1!!.capability.id)
        assertEquals(600, m1.params["seconds"])

        val m2 = CapabilityRegistry.match("timer for 30 seconds")
        assertNotNull(m2)
        assertEquals("clock.timer.set", m2!!.capability.id)
        assertEquals(30, m2.params["seconds"])
    }

    @Test
    fun testSpotifyParameterExtraction() {
        val m = CapabilityRegistry.match("play Bohemian Rhapsody on spotify")
        assertNotNull(m)
        assertEquals("media.spotify.play", m!!.capability.id)
        assertEquals("Bohemian Rhapsody", m.params["query"])
    }

    @Test
    fun testNavigationParameterExtraction() {
        val m = CapabilityRegistry.match("navigate to central station")
        assertNotNull(m)
        assertEquals("navigation.route", m!!.capability.id)
        assertEquals("central station", m.params["destination"])
    }

    @Test
    fun testAppLaunchShortcutMatching() {
        val m1 = CapabilityRegistry.match("open spotify")
        assertNotNull(m1)
        assertEquals("media.spotify.open", m1!!.capability.id)

        val m2 = CapabilityRegistry.match("launch youtube")
        assertNotNull(m2)
        assertEquals("media.youtube.open", m2!!.capability.id)

        val m3 = CapabilityRegistry.match("open whatsapp")
        assertNotNull(m3)
        assertEquals("comm.whatsapp.open", m3!!.capability.id)
    }

    @Test
    fun testFileSearchParameterExtraction() {
        val m = CapabilityRegistry.match("find file invoice.pdf")
        assertNotNull(m)
        assertEquals("file.search", m!!.capability.id)
        assertEquals("invoice.pdf", m.params["query"])
    }

    @Test
    fun testRiskLevels() {
        val deleteCap = CapabilityRegistry.get("file.delete")
        assertNotNull(deleteCap)
        assertEquals(RiskLevel.HIGH, deleteCap!!.risk)
        assertTrue(deleteCap.requiresConfirmation)

        val batteryCap = CapabilityRegistry.get("system.battery")
        assertNotNull(batteryCap)
        assertEquals(RiskLevel.LOW, batteryCap!!.risk)
        assertFalse(batteryCap.requiresConfirmation)
    }

    @Test
    fun testSchemaExport() {
        val schemas = CapabilityRegistry.exportNeedleSchemas()
        assertTrue("Schemas array should export all capabilities", schemas.length() >= 40)
    }

    @Test
    fun testWorkflowsMatching() {
        val m1 = CapabilityRegistry.match("check yourself")
        assertNotNull(m1)
        assertEquals("workflow.diagnose", m1!!.capability.id)

        val m2 = CapabilityRegistry.match("good night jarvis")
        assertNotNull(m2)
        assertEquals("workflow.goodnight", m2!!.capability.id)

        val m3 = CapabilityRegistry.match("start coding")
        assertNotNull(m3)
        assertEquals("workflow.coding", m3!!.capability.id)

        val m4 = CapabilityRegistry.match("focus mode")
        assertNotNull(m4)
        assertEquals("workflow.focus", m4!!.capability.id)
    }

    @Test
    fun testCompoundCommandSplitting() {
        val input = "Turn on flashlight and set volume to 80%"
        assertTrue(com.pr4nav.jarvis.registry.JarvisWorkflowEngine.isCompound(input))
        val parts = com.pr4nav.jarvis.registry.JarvisWorkflowEngine.split(input)
        assertEquals(2, parts.size)
        assertEquals("Turn on flashlight", parts[0])
        assertEquals("set volume to 80%", parts[1])
    }
}
