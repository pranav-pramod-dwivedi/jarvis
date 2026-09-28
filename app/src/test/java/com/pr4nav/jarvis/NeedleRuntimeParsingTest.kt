package com.pr4nav.jarvis

import com.pr4nav.jarvis.needle.NeedleRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

class NeedleRuntimeParsingTest {

    @Test
    fun parseTimer_supportsCompoundDurations() {
        val parsed = NeedleRuntime.parseTimer("set a timer for 1 hour 5 minutes 20 seconds")

        assertEquals(3920, parsed["seconds"])
    }

    @Test
    fun parseAlarm_doesNotTreatRelativeDurationAsClockTime() {
        val parsed = NeedleRuntime.parseAlarm("wake me up in 10 minutes")

        assertEquals(7, parsed["hour"])
        assertEquals(0, parsed["minute"])
    }

    @Test
    fun parseAlarm_acceptsExplicitTime() {
        val parsed = NeedleRuntime.parseAlarm("set an alarm at 7:30 pm")

        assertEquals(19, parsed["hour"])
        assertEquals(30, parsed["minute"])
    }

    @Test
    fun parseAlarm_rejectsInvalidClockMinute() {
        val parsed = NeedleRuntime.parseAlarm("set an alarm at 8:75 pm")

        assertEquals(7, parsed["hour"])
        assertEquals(0, parsed["minute"])
    }
}
