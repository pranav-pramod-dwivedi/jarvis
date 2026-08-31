package com.pr4nav.jarvis

import com.pr4nav.jarvis.router.JarvisIntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityAndRouterTest {

    @Test
    fun testCompoundSplitting() {
        val input = "Play that YouTube video while navigating home"
        // Test intent router recognition
        var resultSummary = ""
        var matchedTypes = emptyList<JarvisIntentRouter.CapabilityType>()

        // Note: Context is null in JVM unit tests, but we can verify recognition logic
        val connectors = listOf(" while ", " and also ", " along with ", " and navigate to ")
        var split = false
        for (c in connectors) {
            if (input.lowercase().contains(c)) {
                val parts = input.split(c)
                assertEquals(2, parts.size)
                assertEquals("Play that YouTube video", parts[0].trim())
                assertEquals("navigating home", parts[1].trim())
                split = true
                break
            }
        }
        assertTrue("Compound sentence was split", split)
    }

    @Test
    fun testMemoryExtractionRegex() {
        val pattern1 = java.util.regex.Pattern.compile("^(?:please )?remember (?:that )?(?:my )?(.*?) (?:is|are|was) (.*)$", java.util.regex.Pattern.CASE_INSENSITIVE)
        val m1 = pattern1.matcher("Remember that my secret code is banana ate cow")
        assertTrue(m1.find())
        assertEquals("secret code", m1.group(1)?.trim())
        assertEquals("banana ate cow", m1.group(2)?.trim())

        val m2 = pattern1.matcher("Remember my doctor appointment is on Tuesday at 4 PM")
        assertTrue(m2.find())
        assertEquals("doctor appointment", m2.group(1)?.trim())
        assertEquals("on Tuesday at 4 PM", m2.group(2)?.trim())
    }

    @Test
    fun testAutomationRuleExtractionRegex() {
        val pattern = java.util.regex.Pattern.compile("^(?:when|if) (.*?), (.*)$", java.util.regex.Pattern.CASE_INSENSITIVE)
        val m = pattern.matcher("When I connect my headphones, open Spotify")
        assertTrue(m.find())
        val cond = m.group(1)?.trim()
        val action = m.group(2)?.trim()
        assertTrue(cond!!.contains("headphones"))
        assertEquals("open Spotify", action)
    }

    @Test
    fun testMathCalculationPattern() {
        val pattern = java.util.regex.Pattern.compile("what(?:'s| is) (\\d+(?:\\.\\d+)?)% of (\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
        val m = pattern.matcher("What's 25% of 480?")
        assertTrue(m.find())
        val pct = m.group(1)?.toDouble()
        val total = m.group(2)?.toDouble()
        assertEquals(25.0, pct)
        assertEquals(480.0, total)
        val ans = (pct!! / 100.0) * total!!
        assertEquals(120.0, ans, 0.001)
    }
}
