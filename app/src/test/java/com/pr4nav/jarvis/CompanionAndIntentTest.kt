package com.pr4nav.jarvis

import com.pr4nav.jarvis.needle.NeedleRouter
import com.pr4nav.jarvis.needle.RouteType
import org.junit.Assert.*
import org.junit.Test

class CompanionAndIntentTest {

    @Test
    fun testNavigationMultilingualPhrases() {
        val phrases = listOf(
            "take me home",
            "navigate to home",
            "open Maps and take me home"
        )
        for (p in phrases) {
            val res = NeedleRouter.route(p)
            assertEquals("Route must be DIRECT_TOOL for '$p'", RouteType.DIRECT_TOOL, res.route)
            assertEquals("Tool must be navigation.route for '$p'", "navigation.route", res.tool)
            assertEquals("home", res.arguments["destination"])
        }
    }

    @Test
    fun testCallingPhrases() {
        val phrases = listOf(
            "call Akhil",
            "dial Akhil",
            "Akhil ko call karo",
            "Akhil ko phone lagao"
        )
        for (p in phrases) {
            val res = NeedleRouter.route(p)
            assertEquals("Route must be DIRECT_TOOL for '$p'", RouteType.DIRECT_TOOL, res.route)
            assertEquals("Tool must be call_contact for '$p'", "call_contact", res.tool)
        }
    }

    @Test
    fun testQuickDeviceControls() {
        val res1 = NeedleRouter.route("turn off flashlight")
        assertEquals(RouteType.DIRECT_TOOL, res1.route)
        assertEquals("system.torch", res1.tool)
        assertEquals(false, res1.arguments["on"])

        val res2 = NeedleRouter.route("check wifi")
        assertEquals(RouteType.DIRECT_TOOL, res2.route)
        assertEquals("get_wifi", res2.tool)

        val res3 = NeedleRouter.route("check bluetooth")
        assertEquals(RouteType.DIRECT_TOOL, res3.route)
        assertEquals("get_bluetooth", res3.tool)
    }
}
