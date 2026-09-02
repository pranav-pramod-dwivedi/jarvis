package com.pr4nav.jarvis.context

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContextManagerTest {

    @Before
    fun setup() {
        ContextManager.clear()
    }

    @Test
    fun testCandidateListDisambiguationSelection() {
        // Step 1: User asks "Call Akhil", system finds 3 contacts
        val candidates = listOf(
            CandidateItem(1, "Akhil Mobile", "+919876543210"),
            CandidateItem(2, "Akhil Work", "+919123456780"),
            CandidateItem(3, "Akhil Home", "+919988776655")
        )
        ContextManager.setCandidateList("call_contact", candidates)

        // Step 2: User says "the first one"
        val res1 = ContextManager.resolveContinuation("the first one")
        assertTrue("Must resolve to candidate action", res1 is ContextContinuationResult.ResolvedAction)
        val action1 = res1 as ContextContinuationResult.ResolvedAction
        assertEquals("call_contact", action1.toolName)
        assertEquals("+919876543210", action1.arguments.optString("number"))
        assertEquals("Akhil Mobile", action1.arguments.optString("contact"))
        assertTrue(action1.reason.contains("candidate #1"))

        // Reset and test numeric selection "2"
        ContextManager.setCandidateList("call_contact", candidates)
        val res2 = ContextManager.resolveContinuation("2")
        assertTrue(res2 is ContextContinuationResult.ResolvedAction)
        val action2 = res2 as ContextContinuationResult.ResolvedAction
        assertEquals("+919123456780", action2.arguments.optString("number"))
        assertEquals("Akhil Work", action2.arguments.optString("contact"))

        // Test natural phrasing "no, second"
        ContextManager.setCandidateList("call_contact", candidates)
        val resNoSecond = ContextManager.resolveContinuation("no, second")
        assertTrue(resNoSecond is ContextContinuationResult.ResolvedAction)
        val actionNoSecond = resNoSecond as ContextContinuationResult.ResolvedAction
        assertEquals("+919123456780", actionNoSecond.arguments.optString("number"))

        // Test cancellation "cancel"
        ContextManager.setCandidateList("call_contact", candidates)
        val resCancel = ContextManager.resolveContinuation("cancel")
        assertTrue(resCancel is ContextContinuationResult.ResolvedText)
        assertEquals("Cancelled.", (resCancel as ContextContinuationResult.ResolvedText).text)
        assertTrue(ContextManager.resolveContinuation("the first one") is ContextContinuationResult.None)
    }

    @Test
    fun testDeviceStateToggleContinuation() {
        // Step 1: User queries Bluetooth status
        ContextManager.updateToolContext("system.bluetooth", JSONObject(), "bluetooth")

        // Step 2: User says "Turn it off"
        val resOff = ContextManager.resolveContinuation("Turn it off")
        assertTrue(resOff is ContextContinuationResult.ResolvedAction)
        val actionOff = resOff as ContextContinuationResult.ResolvedAction
        assertEquals("system.bluetooth", actionOff.toolName)
        assertEquals(false, actionOff.arguments.optBoolean("state"))

        // Step 3: User says "Actually turn it back on"
        val resOn = ContextManager.resolveContinuation("Actually turn it back on")
        assertTrue(resOn is ContextContinuationResult.ResolvedAction)
        val actionOn = resOn as ContextContinuationResult.ResolvedAction
        assertEquals("system.bluetooth", actionOn.toolName)
        assertEquals(true, actionOn.arguments.optBoolean("state"))
    }

    @Test
    fun testPronounResolutionContinuation() {
        ConversationalContext.updateContext("open_app", JSONObject().put("app", "YouTube"))

        val res = ContextManager.resolveContinuation("close this app")
        assertTrue(res is ContextContinuationResult.ResolvedText)
        val textRes = res as ContextContinuationResult.ResolvedText
        assertEquals("close YouTube", textRes.text)
    }

    @Test
    fun testContextExpiry() {
        ContextManager.updateToolContext("system.bluetooth", JSONObject(), "bluetooth")
        assertFalse("Context should not be expired immediately", ContextManager.isExpired())

        ContextManager.clear()
        val res = ContextManager.resolveContinuation("Turn it off")
        assertTrue("Expired/empty context must return None", res is ContextContinuationResult.None)
    }
}
