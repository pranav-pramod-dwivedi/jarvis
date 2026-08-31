package com.pr4nav.jarvis.voice

import org.junit.Assert.*
import org.junit.Test

class WakeWordEngineTest {

    @Test
    fun testWakeWordDetection() {
        val positiveCases = listOf(
            "Jarvis",
            "JARVIS",
            "J-AA-R-V-I-S",
            "hey jarvis",
            "Hey Jarvis, call Akhil",
            "Jarvis, take me home",
            "ok jarvis open maps",
            "Sun Jarvis battery kitni hai",
            "yo jarvis take a screenshot",
            "bhai jarvis call karo",
            "jarvis ghar chalo",
            "javis",
            "jarvees what is the time"
        )

        for (utterance in positiveCases) {
            assertTrue("Should detect wake word in: '$utterance'", WakeWordEngine.containsWakeWord(utterance))
        }

        val negativeCases = listOf(
            "take me home",
            "call Akhil",
            "what is the battery percentage",
            "open chrome",
            "hello there"
        )

        for (utterance in negativeCases) {
            assertFalse("Should NOT detect wake word in: '$utterance'", WakeWordEngine.containsWakeWord(utterance))
        }
    }

    @Test
    fun testStopAndBargeInCommand() {
        val stopCases = listOf(
            "stop",
            "Jarvis stop",
            "shut up",
            "quiet",
            "chup",
            "ruko",
            "bas",
            "cancel"
        )

        for (s in stopCases) {
            assertTrue("Should identify stop command in: '$s'", WakeWordEngine.isStopCommand(s))
        }
    }

    @Test
    fun testExtractCommand() {
        assertEquals("call Akhil", WakeWordEngine.extractCommand("Jarvis, call Akhil"))
        assertEquals("take me home", WakeWordEngine.extractCommand("Hey Jarvis, take me home"))
        assertEquals("battery kitni hai", WakeWordEngine.extractCommand("Sun Jarvis, battery kitni hai"))
        assertEquals("ghar chalo", WakeWordEngine.extractCommand("Jarvis ghar chalo"))
        assertEquals("", WakeWordEngine.extractCommand("Jarvis"))
    }
}
