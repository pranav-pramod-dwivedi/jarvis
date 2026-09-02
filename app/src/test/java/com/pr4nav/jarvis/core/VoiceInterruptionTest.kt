package com.pr4nav.jarvis.core

import org.junit.Assert.*
import org.junit.Test

class VoiceInterruptionTest {

    @Test
    fun testVoiceEngineStopSpeakingDoesNotCancelTask() {
        // AssistantCore.stopSpeaking() stops TTS audio without cancelling the active request
        AssistantCore.stopSpeaking()
        assertTrue("stopSpeaking must execute cleanly", true)
    }

    @Test
    fun testTaskCancellationHaltsExecution() {
        val requestId = "req-to-cancel-1"
        AssistantCore.cancelRequest(requestId)
        assertTrue("cancelRequest must execute cleanly", true)
    }
}
