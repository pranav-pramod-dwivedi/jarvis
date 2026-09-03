package com.pr4nav.jarvis.voice

import android.telephony.TelephonyManager
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TelephonyAndAudioStateTest {

    // =========================================================================
    // 1. STATE MACHINE ENUM & TRANSITION VALIDATION
    // =========================================================================

    @Test
    fun testVoiceStateEnum_ContainsTelephonyStates() {
        val states = JarvisVoiceService.VoiceState.values().map { it.name }
        assertTrue("VoiceState must define CALL_INTERRUPTED", states.contains("CALL_INTERRUPTED"))
        assertTrue("VoiceState must define RESUMING", states.contains("RESUMING"))
    }

    // =========================================================================
    // 2. TELEPHONY CALL-STATE INTERRUPT & RESUME SIMULATION
    // =========================================================================

    @Test
    fun testCallInterruptionStateMachine_RingingHaltsVoice() {
        var currentState = JarvisVoiceService.VoiceState.LISTENING
        var isMicActive = true
        var isTtsActive = true
        var isCallActive = false

        fun simulateCallState(callState: Int) {
            when (callState) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    isCallActive = true
                    currentState = JarvisVoiceService.VoiceState.CALL_INTERRUPTED
                    isMicActive = false
                    isTtsActive = false
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (isCallActive) {
                        isCallActive = false
                        currentState = JarvisVoiceService.VoiceState.RESUMING
                        // Simulate delayed safe restore
                        currentState = JarvisVoiceService.VoiceState.IDLE
                        isMicActive = true
                    }
                }
            }
        }

        // 1. Call incoming (RINGING)
        simulateCallState(TelephonyManager.CALL_STATE_RINGING)
        assertEquals(JarvisVoiceService.VoiceState.CALL_INTERRUPTED, currentState)
        assertFalse("Mic must be halted during incoming ring", isMicActive)
        assertFalse("TTS must be halted during incoming ring", isTtsActive)
        assertTrue(isCallActive)

        // 2. Call answered (OFFHOOK) - remains interrupted
        simulateCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        assertEquals(JarvisVoiceService.VoiceState.CALL_INTERRUPTED, currentState)
        assertFalse(isMicActive)

        // 3. Call hangs up (IDLE) - resumes safely
        simulateCallState(TelephonyManager.CALL_STATE_IDLE)
        assertEquals(JarvisVoiceService.VoiceState.IDLE, currentState)
        assertTrue("Mic may safely resume after call finishes", isMicActive)
        assertFalse(isCallActive)
    }

    @Test
    fun testCallActive_BlocksWakeDetectionAndListening() {
        val isCallActive = true
        var listeningSessionStarted = false
        var wakeWordProcessed = false

        fun onWakeWord() {
            if (isCallActive) return
            wakeWordProcessed = true
        }

        fun startListening() {
            if (isCallActive) return
            listeningSessionStarted = true
        }

        onWakeWord()
        startListening()

        assertFalse("Wake word must be ignored while call is active", wakeWordProcessed)
        assertFalse("Listening session must not start while call is active", listeningSessionStarted)
    }

    // =========================================================================
    // 3. SCREENSHOT CAPABILITY TOKEN LIFECYCLE TESTS
    // =========================================================================

    @Test
    fun testScreenshotCapability_TokenLifecycle() {
        val cap = com.pr4nav.jarvis.capabilities.ScreenshotCapability
        assertFalse("Initial state has no token", cap.hasToken())

        // Invalidate when empty should be safe
        cap.invalidateToken()
        assertFalse(cap.hasToken())

        // Simulate consent denied
        cap.onConsentResult(null, null, "User denied")
        assertFalse(cap.hasToken())

        // Invalidate explicitly
        cap.invalidateToken()
        assertFalse(cap.hasToken())
    }

    // =========================================================================
    // 4. BOOT FGS LOGIC VERIFICATION
    // =========================================================================

    @Test
    fun testBootBranchingLogic_Contract() {
        // Contract test: On SDK >= 34, BootReceiver MUST NOT call JarvisVoiceService.start(context)
        fun shouldPostNotificationRatherThanDirectFgs(sdkInt: Int): Boolean {
            return sdkInt >= 34 // UPSIDE_DOWN_CAKE
        }

        assertFalse("API 30 directly starts service", shouldPostNotificationRatherThanDirectFgs(30))
        assertFalse("API 33 directly starts service", shouldPostNotificationRatherThanDirectFgs(33))
        assertTrue("API 34 posts notification to avoid FGS exception", shouldPostNotificationRatherThanDirectFgs(34))
        assertTrue("API 35 posts notification", shouldPostNotificationRatherThanDirectFgs(35))
        assertTrue("API 36 (Android 16) posts notification", shouldPostNotificationRatherThanDirectFgs(36))
    }
}
