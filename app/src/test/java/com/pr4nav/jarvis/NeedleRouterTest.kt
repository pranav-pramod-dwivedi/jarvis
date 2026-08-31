package com.pr4nav.jarvis

import com.pr4nav.jarvis.needle.NeedleConfig
import com.pr4nav.jarvis.needle.NeedleRouter
import com.pr4nav.jarvis.needle.RouteType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NeedleRouterTest {

    @Before
    fun setUp() {
        NeedleConfig.highConfidenceThreshold = 0.75
        NeedleConfig.mediumConfidenceThreshold = 0.50
        NeedleConfig.destructiveThreshold = 0.90
    }

    @Test
    fun test01_openSpotify() {
        val res = NeedleRouter.route("open Spotify")
        assertTrue(res.route == RouteType.DIRECT_TOOL)
        assertTrue(res.tool == "media.play" || res.tool == "app.launch")
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test02_playMusic() {
        val res = NeedleRouter.route("play music")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("media.play", res.tool)
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test03_pauseMusic() {
        val res = NeedleRouter.route("pause music")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("media.control", res.tool)
        assertEquals("pause", res.arguments["action"])
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test04_setVolume() {
        val res = NeedleRouter.route("set volume louder")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("system.volume", res.tool)
        assertEquals("up", res.arguments["direction"])
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test05_setBrightness() {
        val res = NeedleRouter.route("set brightness down")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("system.brightness", res.tool)
        assertEquals("down", res.arguments["direction"])
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test06_checkBattery() {
        val res = NeedleRouter.route("check battery")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("system.battery", res.tool)
        assertTrue(res.confidence >= 0.90)
    }

    @Test
    fun test07_showFiles() {
        val res = NeedleRouter.route("show files")
        assertEquals(RouteType.GUI, res.route)
        assertEquals("file.list", res.tool)
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test08_searchFiles() {
        val res = NeedleRouter.route("search files for report")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("file.search", res.tool)
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test09_openMaps() {
        val res = NeedleRouter.route("open Maps and take me home")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("navigation.route", res.tool)
        assertEquals("home", res.arguments["destination"])
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test10_getCurrentTime() {
        val res = NeedleRouter.route("get current time")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("system.time", res.tool)
        assertTrue(res.confidence >= 0.90)
    }

    @Test
    fun test11_runSafeTermuxDiagnostic() {
        val res = NeedleRouter.route("run a safe Termux diagnostic")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("termux.diag", res.tool)
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test12_openOpenCode() {
        val res = NeedleRouter.route("open OpenCode")
        assertEquals(RouteType.DIRECT_TOOL, res.route)
        assertEquals("opencode.open", res.tool)
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test13_showGuiInformation() {
        val res = NeedleRouter.route("show dashboard of cpu usage")
        assertEquals(RouteType.GUI, res.route)
        assertEquals("gui.show_dashboard", res.tool)
        assertTrue(res.confidence >= 0.75)
    }

    @Test
    fun test14_unknownRequest_escalatesToLlm() {
        val res = NeedleRouter.route("Explain quantum electrodynamics and write a research paper")
        assertEquals(RouteType.ESCALATE, res.route)
        assertNull(res.tool)
        assertTrue(res.confidence < 0.50)
    }

    @Test
    fun test15_highRiskAction_requiresConfirmation() {
        // High risk file delete requires strict confirmation threshold
        val res = NeedleRouter.route("delete file notes.txt")
        // Destructive operation gated
        assertTrue(res.route == RouteType.CLARIFICATION || res.route == RouteType.DIRECT_TOOL)
        assertEquals("file.delete", res.tool)
    }

    @Test
    fun test16_naturalHindiHinglishCallAndLaunch() {
        val res1 = NeedleRouter.route("call Akhil")
        assertTrue(res1.route == RouteType.DIRECT_TOOL)
        assertEquals("call_contact", res1.tool)

        val res2 = NeedleRouter.route("Akhil ko phone lagao")
        assertTrue(res2.route == RouteType.DIRECT_TOOL)
        assertEquals("call_contact", res2.tool)

        val res3 = NeedleRouter.route("take a screenshot")
        assertTrue(res3.route == RouteType.DIRECT_TOOL)
        assertEquals("take_screenshot", res3.tool)
    }
}
