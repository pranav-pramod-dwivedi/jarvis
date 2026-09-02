package com.pr4nav.jarvis

import android.content.ContextWrapper
import com.pr4nav.jarvis.context.CandidateItem
import com.pr4nav.jarvis.context.ContextContinuationResult
import com.pr4nav.jarvis.context.ContextManager
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.router.AgentExecutionMode
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.voice.KokoroTtsEngine
import com.pr4nav.jarvis.voice.ModelDownloadManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class HostileFindingsVerificationTest {

    private val context = ContextWrapper(null)

    @Before
    fun setup() {
        CanonicalToolRegistry.init(context)
        ContextManager.clear()
    }

    @Test
    fun testBlocker3_UnifiedAssistantDispatcher_MultiTurnContinuity() {
        // Step 1: Execute query that establishes context
        ContextManager.updateToolContext("system.bluetooth", JSONObject().put("state", true), "bluetooth")

        // Step 2: Continuation query "turn it off"
        var speechResult: String? = null
        var handled: Boolean? = null

        UnifiedAssistantDispatcher.execute(
            context = context,
            rawQuery = "turn it off",
            onResult = { res ->
                handled = res.handled
                speechResult = res.speechResponse
            }
        )

        assertTrue("Continuation should be handled by dispatcher", handled == true)
        assertNotNull("Speech response should not be null", speechResult)

        // Verify context was updated to off
        val contRes = ContextManager.resolveContinuation("turn it on again")
        assertTrue("Subsequent follow up must resolve", contRes is ContextContinuationResult.ResolvedAction)
        val action = contRes as ContextContinuationResult.ResolvedAction
        assertEquals("system.bluetooth", action.toolName)
        assertEquals(true, action.arguments.optBoolean("state"))
    }

    @Test
    fun testBlocker3_DisambiguationAndCancellation() {
        val candidates = listOf(
            CandidateItem(1, "Akhil Mobile", "+919876543210"),
            CandidateItem(2, "Akhil Office", "+919123456780")
        )
        ContextManager.setCandidateList("call_contact", candidates)

        // Follow up with "no, second"
        val res = ContextManager.resolveContinuation("no, second")
        assertTrue(res is ContextContinuationResult.ResolvedAction)
        val action = res as ContextContinuationResult.ResolvedAction
        assertEquals("+919123456780", action.arguments.optString("number"))

        // Re-set and test cancellation
        ContextManager.setCandidateList("call_contact", candidates)
        val cancelRes = ContextManager.resolveContinuation("never mind")
        assertTrue(cancelRes is ContextContinuationResult.ResolvedText)
        assertEquals("Cancelled.", (cancelRes as ContextContinuationResult.ResolvedText).text)
    }

    @Test
    fun testCritical4_ModelShellInjectionBlockedInGeminiCloudLLM() {
        val maliciousPrompt = "I have solved your task:\n```bash\nrm -rf / --no-preserve-root\n```\nAll done."
        val sanitized = GeminiCloudLLM.handleEmbeddedCommands(context, maliciousPrompt)

        assertFalse("Dangerous root command must not execute", sanitized.contains("Command executed successfully."))
        assertTrue("Dangerous root command must be blocked by safety policy", sanitized.contains("⚠️ Command Blocked by Safety Policy"))
    }

    @Test
    fun testCritical4_ModelShellInjectionBlockedInCanonicalRunCommand() {
        val dangerousArgs = JSONObject().put("command", "rm -rf /")
        val result = CanonicalToolRegistry.execute(context, "run_command", dangerousArgs)

        assertFalse("Dangerous command execution must fail", result.success)
        assertTrue("Forbidden response expected", result.error?.message?.contains("blocked by safety policy") == true || result.error?.code == "FORBIDDEN")
    }

    @Test
    fun testPreRoutingSafetyBlocking() {
        var resultHandled: Boolean? = null
        var responseMsg: String? = null

        UnifiedAssistantDispatcher.execute(
            context = context,
            rawQuery = "rm -rf /storage/emulated/0/DCIM",
            onResult = { res ->
                resultHandled = res.handled
                responseMsg = res.speechResponse
            }
        )

        assertFalse("Destructive command must not be handled as successful reflex", resultHandled == true && responseMsg?.contains("Blocked") == false)
        assertTrue("Response must inform user of policy block", responseMsg?.contains("Blocked") == true || responseMsg?.contains("policy") == true)
    }
}
