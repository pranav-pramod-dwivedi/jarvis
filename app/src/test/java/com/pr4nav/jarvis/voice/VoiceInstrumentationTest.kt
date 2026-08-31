package com.pr4nav.jarvis.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceInstrumentationTest {

    @Test
    fun testInstrumentationTracking() {
        val initialStarts = VoiceInstrumentation.sttSessionsStarted
        val initialStops = VoiceInstrumentation.sttSessionsCompleted
        val initialWake = VoiceInstrumentation.wakeWordActivations
        val initialFalse = VoiceInstrumentation.falseActivations
        val initialVad = VoiceInstrumentation.vadEvents

        VoiceInstrumentation.onVadEvent("Ambient voice candidate detected", "IDLE")
        assertEquals(initialVad + 1, VoiceInstrumentation.vadEvents)

        VoiceInstrumentation.onWakeWordConfirmed("Jarvis", "IDLE")
        assertEquals(initialWake + 1, VoiceInstrumentation.wakeWordActivations)

        VoiceInstrumentation.onFalseActivation("TV background speech", "LISTENING")
        assertEquals(initialFalse + 1, VoiceInstrumentation.falseActivations)

        VoiceInstrumentation.onListenerStart("User Trigger", "IDLE")
        assertEquals(initialStarts + 1, VoiceInstrumentation.sttSessionsStarted)
        assertEquals("User Trigger", VoiceInstrumentation.lastStartReason)

        VoiceInstrumentation.onListenerStop("Results received", "LISTENING")
        assertEquals(initialStops + 1, VoiceInstrumentation.sttSessionsCompleted)
        assertEquals("Results received", VoiceInstrumentation.lastStopReason)

        VoiceInstrumentation.onError(7, "No match", "LISTENING")
        assertTrue(VoiceInstrumentation.sttErrors > 0)

        VoiceInstrumentation.onSuccess("Jarvis take me home", "LISTENING")
    }
}
