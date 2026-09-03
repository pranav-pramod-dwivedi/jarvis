package com.pr4nav.jarvis.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceStateTest {

    @Test
    fun testVoiceServiceStates() {
        val states = JarvisVoiceService.VoiceState.values()
        val expected = setOf(
            "OFF",
            "IDLE",
            "WAKE_DETECTED",
            "STARTING_LISTENER",
            "LISTENING",
            "PROCESSING",
            "SPEAKING",
            "FOLLOW_UP_LISTENING",
            "CALL_INTERRUPTED",
            "RESUMING",
            "PAUSED",
            "ERROR",
            "PERMISSION_REQUIRED"
        )

        assertEquals(expected.size, states.size)
        for (name in expected) {
            assertTrue("State $name should exist", states.any { it.name == name })
        }
    }

    @Test
    fun testCommandCleaningVariations() {
        assertEquals("open WhatsApp", WakeWordEngine.extractCommand("Hey Jarvis, open WhatsApp"))
        assertEquals("battery kitni hai?", WakeWordEngine.extractCommand("Jarvis battery kitni hai?"))
        assertEquals("take me home", WakeWordEngine.extractCommand("JARVIS, take me home"))
        assertEquals("call Akhil", WakeWordEngine.extractCommand("Jarvis, call Akhil"))
        assertEquals("find Physics notes", WakeWordEngine.extractCommand("bhai jarvis please find Physics notes"))
    }
}
