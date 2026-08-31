package com.pr4nav.jarvis

import com.pr4nav.jarvis.router.LanguageNormalizer
import org.junit.Assert.*
import org.junit.Test

class LanguageNormalizerTest {

    @Test
    fun testEnglishAppLaunch() {
        val r1 = LanguageNormalizer.normalize("open chrome")
        assertNotNull(r1)
        assertEquals("open_app", r1!!.tool)
        assertEquals("Chrome", r1.args.getString("app"))

        val r2 = LanguageNormalizer.normalize("launch youtube")
        assertNotNull(r2)
        assertEquals("open_app", r2!!.tool)
        assertEquals("YouTube", r2.args.getString("app"))
    }

    @Test
    fun testHinglishAndHindiAppLaunch() {
        val r1 = LanguageNormalizer.normalize("chrome kholo")
        assertNotNull(r1)
        assertEquals("open_app", r1!!.tool)
        assertEquals("Chrome", r1.args.getString("app"))

        val r2 = LanguageNormalizer.normalize("whatsapp open karo")
        assertNotNull(r2)
        assertEquals("open_app", r2!!.tool)
        assertEquals("WhatsApp", r2.args.getString("app"))

        val r3 = LanguageNormalizer.normalize("kholo spotify")
        assertNotNull(r3)
        assertEquals("open_app", r3!!.tool)
        assertEquals("Spotify", r3.args.getString("app"))
    }

    @Test
    fun testNavigationEnglishAndHinglish() {
        val r1 = LanguageNormalizer.normalize("take me home")
        assertNotNull(r1)
        assertEquals("navigate", r1!!.tool)
        assertEquals("home", r1.args.getString("destination"))

        val r2 = LanguageNormalizer.normalize("ghar ka rasta bata")
        assertNotNull(r2)
        assertEquals("navigate", r2!!.tool)
        assertEquals("home", r2.args.getString("destination"))

        val r3 = LanguageNormalizer.normalize("delhi ka rasta batao")
        assertNotNull(r3)
        assertEquals("navigate", r3!!.tool)
        assertEquals("delhi", r3.args.getString("destination"))

        val r4 = LanguageNormalizer.normalize("navigate to airport")
        assertNotNull(r4)
        assertEquals("navigate", r4!!.tool)
        assertEquals("airport", r4.args.getString("destination"))
    }

    @Test
    fun testPhoneCallEnglishAndHinglish() {
        val r1 = LanguageNormalizer.normalize("call mom")
        assertNotNull(r1)
        assertEquals("call_contact", r1!!.tool)
        assertEquals("mom", r1.args.getString("number"))

        val r2 = LanguageNormalizer.normalize("mummy ko call lagao")
        assertNotNull(r2)
        assertEquals("call_contact", r2!!.tool)
        assertEquals("mummy", r2.args.getString("number"))

        val r3 = LanguageNormalizer.normalize("dial 9876543210")
        assertNotNull(r3)
        assertEquals("call_contact", r3!!.tool)
        assertEquals("9876543210", r3.args.getString("number"))
    }

    @Test
    fun testBatteryQueries() {
        val r1 = LanguageNormalizer.normalize("battery kitni hai")
        assertNotNull(r1)
        assertEquals("get_battery", r1!!.tool)

        val r2 = LanguageNormalizer.normalize("what is my battery")
        assertNotNull(r2)
        assertEquals("get_battery", r2!!.tool)
    }

    @Test
    fun testLocationQueries() {
        val r1 = LanguageNormalizer.normalize("where am I")
        assertNotNull(r1)
        assertEquals("get_location", r1!!.tool)

        val r2 = LanguageNormalizer.normalize("meri location kya hai")
        assertNotNull(r2)
        assertEquals("get_location", r2!!.tool)
    }

    @Test
    fun testScreenshotAndSettings() {
        val r1 = LanguageNormalizer.normalize("take screenshot")
        assertNotNull(r1)
        assertEquals("take_screenshot", r1!!.tool)

        val r2 = LanguageNormalizer.normalize("screenshot lo")
        assertNotNull(r2)
        assertEquals("take_screenshot", r2!!.tool)

        val r3 = LanguageNormalizer.normalize("wifi settings kholo")
        assertNotNull(r3)
        assertEquals("open_settings", r3!!.tool)
        assertEquals("wifi", r3.args.getString("subpage"))
    }

    @Test
    fun testUnknownQueryReturnsNull() {
        val r = LanguageNormalizer.normalize("write a poem about quantum gravity in french")
        assertNull(r)
    }
}
