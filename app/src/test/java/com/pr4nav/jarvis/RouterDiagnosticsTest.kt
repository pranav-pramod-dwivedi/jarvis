package com.pr4nav.jarvis

import com.pr4nav.jarvis.router.ActivityEvent
import com.pr4nav.jarvis.router.ActivityState
import com.pr4nav.jarvis.router.RouterDiagnosticTrace
import com.pr4nav.jarvis.router.RouterDiagnostics
import com.pr4nav.jarvis.router.TaskCategory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RouterDiagnosticsTest {

    @Before
    fun setup() {
        RouterDiagnostics.clear()
    }

    @Test
    fun testRecordsAndRetrievesTraces() {
        val trace = RouterDiagnosticTrace(
            input = "Turn on bluetooth",
            category = TaskCategory.DEVICE_COMMAND,
            classificationConfidence = 0.98f,
            routeSelected = "NEEDLE_DETERMINISTIC",
            modelEngine = "Needle Reflex Executor",
            toolRequested = "system.bluetooth",
            toolArguments = "{\"state\":true}",
            executionResult = "OK",
            finalResponse = "Bluetooth enabled.",
            latencyMs = 12L,
            events = listOf(
                ActivityEvent(ActivityState.UNDERSTANDING, "Analyzing query intent…"),
                ActivityEvent(ActivityState.EXECUTING, "Executing system.bluetooth…"),
                ActivityEvent(ActivityState.DONE, "Device command completed")
            )
        )

        RouterDiagnostics.record(trace)
        val recent = RouterDiagnostics.getRecentTraces(10)
        assertEquals(1, recent.size)
        assertEquals("Turn on bluetooth", recent[0].input)
        assertEquals(TaskCategory.DEVICE_COMMAND, recent[0].category)
        assertEquals(3, recent[0].events.size)

        val report = RouterDiagnostics.toFullDiagnosticsReport()
        assertTrue(report.contains("Total Recorded Traces: 1"))
        assertTrue(report.contains("system.bluetooth"))
        assertTrue(report.contains("Bluetooth enabled."))
    }

    @Test
    fun testRingBufferCapacityLimit() {
        for (i in 1..60) {
            RouterDiagnostics.record(
                RouterDiagnosticTrace(
                    input = "Query #$i",
                    category = TaskCategory.CASUAL,
                    classificationConfidence = 0.90f,
                    routeSelected = "LOCAL_QWEN_CASUAL",
                    modelEngine = "Local Qwen Engine",
                    finalResponse = "Answer #$i",
                    latencyMs = 50L
                )
            )
        }

        val all = RouterDiagnostics.getRecentTraces(100)
        assertEquals(50, all.size) // Capped at 50 max traces
        assertEquals("Query #60", all.first().input)
    }

    @Test
    fun testActivityEventStatusFormatting() {
        val ev = ActivityEvent(ActivityState.EXECUTING, "Running shell tool")
        assertEquals("● Executing · Running shell tool", ev.toStatusString())

        val done = ActivityEvent(ActivityState.DONE, "Completed")
        assertEquals("✓ Done · Completed", done.toStatusString())
    }
}
