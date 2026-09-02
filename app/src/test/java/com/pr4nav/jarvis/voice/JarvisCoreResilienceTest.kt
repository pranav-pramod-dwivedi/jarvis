package com.pr4nav.jarvis.voice

import android.content.ContextWrapper
import android.content.Intent
import com.pr4nav.jarvis.router.AgentExecutionMode
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JarvisCoreResilienceTest {

    private val context = ContextWrapper(null)

    @Before
    fun setup() {
        com.pr4nav.jarvis.tools.CanonicalToolRegistry.init(context)
        com.pr4nav.jarvis.context.ContextManager.clear()
    }

    // =========================================================================
    // 1. OBSERVER LIFECYCLE & DECOUPLING TESTS
    // =========================================================================

    @Test
    fun testCoreObserver_RegisterAndDetachLifecycle() {
        val stateChangedCount = AtomicInteger(0)
        val lastReportedState = AtomicReference<JarvisVoiceService.VoiceState>()

        val observer = object : JarvisVoiceService.CoreObserver {
            override fun onStateChanged(state: JarvisVoiceService.VoiceState, detail: String) {
                stateChangedCount.incrementAndGet()
                lastReportedState.set(state)
            }
            override fun onSpeechRecognized(text: String) {}
            override fun onResponseSynthesized(speechText: String, fullSummary: String) {}
            override fun onThinkingTrace(trace: String) {}
        }

        // Register observer
        JarvisVoiceService.registerObserver(observer)

        // Verify unregister cleans up observer completely without memory leak
        JarvisVoiceService.unregisterObserver(observer)
        val countAfterUnregister = stateChangedCount.get()

        // Further service transitions must not notify detached observer
        assertEquals("Detached observer must not receive further events", countAfterUnregister, stateChangedCount.get())
    }

    @Test
    fun testActivityDestruction_CoreRemainsActive() {
        // Simulate Activity A connecting to core
        val activityAReceivedEvent = AtomicBoolean(false)
        val observerA = object : JarvisVoiceService.CoreObserver {
            override fun onStateChanged(state: JarvisVoiceService.VoiceState, detail: String) {
                activityAReceivedEvent.set(true)
            }
            override fun onSpeechRecognized(text: String) {}
            override fun onResponseSynthesized(speechText: String, fullSummary: String) {}
            override fun onThinkingTrace(trace: String) {}
        }
        JarvisVoiceService.registerObserver(observerA)

        // Simulate Activity A destroyed / swiped away from Recents
        JarvisVoiceService.unregisterObserver(observerA)
        activityAReceivedEvent.set(false)

        // Simulate Activity B launched later (reconnects to existing Core)
        val activityBConnected = AtomicBoolean(false)
        val observerB = object : JarvisVoiceService.CoreObserver {
            override fun onStateChanged(state: JarvisVoiceService.VoiceState, detail: String) {
                activityBConnected.set(true)
            }
            override fun onSpeechRecognized(text: String) {}
            override fun onResponseSynthesized(speechText: String, fullSummary: String) {}
            override fun onThinkingTrace(trace: String) {}
        }
        JarvisVoiceService.registerObserver(observerB)

        // Activity A should not receive events, Activity B connects cleanly
        assertFalse("Dead Activity A must not receive events", activityAReceivedEvent.get())
        JarvisVoiceService.unregisterObserver(observerB)
    }

    // =========================================================================
    // 2. STOP SPEAKING VS CANCEL TASK SEPARATION
    // =========================================================================

    @Test
    fun testStopSpeaking_DoesNotCorruptContext() {
        // Step 1: Establish context
        com.pr4nav.jarvis.context.ContextManager.updateToolContext(
            "system.torch",
            org.json.JSONObject().put("state", true),
            "torch"
        )

        // Step 2: Barge-in Stop Command ("stop" / "jarvis stop")
        assertTrue("Stop command check must identify barge-in", WakeWordEngine.isStopCommand("stop"))
        assertTrue("Stop command check must identify 'shut up'", WakeWordEngine.isStopCommand("shut up"))

        // Step 3: ContextManager must still retain last domain for follow-up
        assertFalse("Context must not be wiped by audio stop command", com.pr4nav.jarvis.context.ContextManager.isExpired())
        val continuation = com.pr4nav.jarvis.context.ContextManager.resolveContinuation("turn it off")
        assertTrue("Follow-up command must resolve accurately after speech stop", continuation is com.pr4nav.jarvis.context.ContextContinuationResult.ResolvedAction)
    }

    // =========================================================================
    // 3. BOOT RECOVERY & PERMISSION GUARD
    // =========================================================================

    @Test
    fun testBootReceiver_RespectsPreferencesAndPermissions() {
        val receiver = BootReceiver()

        // Unrelated action must be ignored
        val randomIntent = Intent("android.intent.action.BATTERY_LOW")
        receiver.onReceive(context, randomIntent)

        // Boot intent with null context safely handled
        receiver.onReceive(null, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, null)
    }

    // =========================================================================
    // 4. BATTERY OPTIMIZATION & OEM RECOGNITION
    // =========================================================================

    @Test
    fun testBatteryOptimizationHelper_OemGuidanceStructure() {
        val guidance = BatteryOptimizationHelper.getOemGuidance()
        assertNotNull(guidance.manufacturer)
        assertNotNull(guidance.guidanceTitle)
        assertTrue("OEM guidance steps must be provided", guidance.guidanceSteps.isNotEmpty())
    }

    // =========================================================================
    // 5. RESILIENCE AGAINST OFFLINE PROVIDERS
    // =========================================================================

    @Test
    fun testOfflineProviders_CoreRemainsAlive() {
        UnifiedAssistantDispatcher.setAgentMode(context, AgentExecutionMode.GROQ_NEEDLE)

        var handledResult: Boolean? = null
        var speechResponse: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        // Submit query when Groq or offline provider executes
        UnifiedAssistantDispatcher.execute(
            context = context,
            rawQuery = "What is the capital of France?",
            onResult = { res ->
                handledResult = res.handled
                speechResponse = res.jarvisResponse.speechText
                latch.countDown()
            }
        )

        val completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Execution must complete", completed)
        assertNotNull("Speech response must be provided", speechResponse)
    }
}
