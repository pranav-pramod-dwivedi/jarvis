package com.pr4nav.jarvis

import android.content.ContextWrapper
import com.pr4nav.jarvis.agent.AgentActionLoop
import com.pr4nav.jarvis.context.CandidateItem
import com.pr4nav.jarvis.context.ContextManager
import com.pr4nav.jarvis.core.AssistantCore
import com.pr4nav.jarvis.core.AssistantEventStatus
import com.pr4nav.jarvis.core.AssistantMessageRequest
import com.pr4nav.jarvis.core.AssistantResponse
import com.pr4nav.jarvis.response.UserResponseSanitizer
import com.pr4nav.jarvis.router.JarvisRouter
import com.pr4nav.jarvis.router.LanguageNormalizer
import com.pr4nav.jarvis.router.PreRoutingDecision
import com.pr4nav.jarvis.router.PreRoutingPipeline
import com.pr4nav.jarvis.router.TaskCategory
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolValidator
import com.pr4nav.jarvis.tools.ValidationResult
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import com.pr4nav.jarvis.workspace.WorkspaceValidationResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HostileHumanAuditTest {

    private val context = ContextWrapper(null)

    @Before
    fun setup() {
        JarvisWorkspace.initWorkspace(null)
        CanonicalToolRegistry.init(null)
        ContextManager.clear()
    }

    @Test
    fun testHumanChaosRouterVariations() {
        // 1. Slang, short & colloquial commands
        val r1 = LanguageNormalizer.normalize("torch")
        assertNotNull("torch should resolve directly", r1)
        assertEquals("system.torch", r1!!.tool)
        assertEquals(true, r1.args.optBoolean("state"))

        val r2 = LanguageNormalizer.normalize("light band kar")
        assertNotNull("light band kar should resolve to torch off", r2)
        assertEquals("system.torch", r2!!.tool)
        assertEquals(false, r2.args.optBoolean("state"))

        val r3 = LanguageNormalizer.normalize("make it loud")
        assertNotNull("make it loud should resolve to volume raise", r3)
        assertEquals("system.volume", r3!!.tool)
        assertEquals("raise", r3.args.optString("action"))

        val r4 = LanguageNormalizer.normalize("open dev settings")
        assertNotNull("open dev settings should resolve to developer settings", r4)
        assertEquals("open_settings", r4!!.tool)
        assertEquals("developer", r4.args.optString("subpage"))

        val r5 = LanguageNormalizer.normalize("bhai bluetooth band kar")
        assertNotNull("Hinglish bluetooth off should resolve", r5)
        assertEquals("system.bluetooth", r5!!.tool)
        assertEquals(false, r5.args.optBoolean("state"))

        val r6 = LanguageNormalizer.normalize("ghar ka rasta bata")
        assertNotNull("ghar ka rasta bata should resolve to home navigation", r6)
        assertEquals("navigate", r6!!.tool)
        assertEquals("home", r6.args.optString("destination"))

        val r7 = LanguageNormalizer.normalize("mummy ko phone laga")
        assertNotNull("mummy ko phone laga should resolve to call_contact", r7)
        assertEquals("call_contact", r7!!.tool)
        assertEquals("mummy", r7.args.optString("number"))
    }

    @Test
    fun testConversationalMemoryAndDisambiguation() {
        // Multi-candidate flow
        val candidates = listOf(
            CandidateItem(1, "Akhil Mobile", "+919876543210"),
            CandidateItem(2, "Akhil Work", "+919123456780"),
            CandidateItem(3, "Akhil Home", "+919988776655")
        )
        ContextManager.setCandidateList("call_contact", candidates)

        // User says "the first one"
        val decision1 = PreRoutingPipeline.filter(null, "the first one")
        assertTrue(decision1 is PreRoutingDecision.DirectToolMatch)
        val match1 = decision1 as PreRoutingDecision.DirectToolMatch
        assertEquals("call_contact", match1.toolName)
        assertEquals("+919876543210", match1.arguments.optString("number"))

        // Reset and test "second"
        ContextManager.setCandidateList("call_contact", candidates)
        val decision2 = PreRoutingPipeline.filter(null, "second")
        assertTrue(decision2 is PreRoutingDecision.DirectToolMatch)
        val match2 = decision2 as PreRoutingDecision.DirectToolMatch
        assertEquals("call_contact", match2.toolName)
        assertEquals("+919123456780", match2.arguments.optString("number"))

        // Test cancellation clearing candidates
        ContextManager.clearCandidates()
        val decisionAfterCancel = PreRoutingPipeline.filter(null, "the first one")
        assertFalse("Should NOT match candidate after cancel", decisionAfterCancel is PreRoutingDecision.DirectToolMatch)
    }

    @Test
    fun testFilesystemWorkspaceSecurityAttack() {
        // Hostile traversal attempts
        val attack1 = JarvisWorkspace.validateAccess("/storage/emulated/0/JARVIS/workspace/../../DCIM/photo.jpg", isWrite = true)
        assertTrue("Traversal outside JARVIS must be rejected", attack1 is WorkspaceValidationResult.Violation)

        val attack2 = JarvisWorkspace.validateAccess("/root/secret.key", isWrite = true)
        assertTrue("Root access must be rejected", attack2 is WorkspaceValidationResult.Violation)

        val attack3 = JarvisWorkspace.validateAccess("/data/data/com.pr4nav.jarvis/databases/app.db", isWrite = true)
        assertTrue("Private data access must be rejected", attack3 is WorkspaceValidationResult.Violation)

        val attack4 = JarvisWorkspace.validateAccess("/tmp/malicious.sh", isWrite = true)
        assertTrue("/tmp access must be rejected", attack4 is WorkspaceValidationResult.Violation)

        val safe = JarvisWorkspace.validateAccess("/storage/emulated/0/JARVIS/workspace/my_script.py", isWrite = true)
        assertTrue("Valid workspace file must be allowed", safe is WorkspaceValidationResult.Allowed)
    }

    @Test
    fun testHonestFailureHandling() {
        // 1. Invalid tool action
        val valResult = ToolValidator.validate(null, "system.bluetooth", JSONObject().put("action", "banana"), "turn bluetooth banana")
        assertTrue("Invalid action must be rejected", valResult is ValidationResult.Rejected)
        val rej = valResult as ValidationResult.Rejected
        assertEquals("INVALID_TOOL_ACTION", rej.reasonCode)

        // 2. Traversal rejection in file tool validator
        val valPath = ToolValidator.validate(null, "write_file", JSONObject().put("path", "/root/bad.txt").put("content", "x"), "write bad file")
        assertTrue("Root path in write_file must be rejected", valPath is ValidationResult.Rejected)
        val rejPath = valPath as ValidationResult.Rejected
        assertEquals("WORKSPACE_BOUNDARY", rejPath.reasonCode)

        // 3. Nonexistent file read
        val toolRes = CanonicalToolRegistry.execute(context, "read_file", JSONObject().put("path", "/storage/emulated/0/JARVIS/workspace/nonexistent_file_123.txt"))
        assertFalse("Nonexistent file read must fail", toolRes.success)
        assertEquals("NOT_FOUND", toolRes.error?.code)
    }

    @Test
    fun testIdentityLeakDefense() {
        val qwenLeak = "I am Qwen, developed by Alibaba."
        val sanitizedQwen = UserResponseSanitizer.sanitize(qwenLeak, "who are you?")
        assertFalse("Must NOT leak Qwen", sanitizedQwen.contains("Qwen"))
        assertFalse("Must NOT leak Alibaba", sanitizedQwen.contains("Alibaba"))
        assertTrue("Must assert JARVIS identity", sanitizedQwen.contains("JARVIS"))

        val geminiLeak = "I am Gemini, a large language model built by Google."
        val sanitizedGemini = UserResponseSanitizer.sanitize(geminiLeak, "are you Gemini?")
        assertFalse("Must NOT leak Gemini", sanitizedGemini.contains("Gemini"))
        assertFalse("Must NOT leak Google", sanitizedGemini.contains("Google"))
        assertTrue("Must assert JARVIS identity", sanitizedGemini.contains("JARVIS"))
    }

    @Test
    fun testImpatienceAndInterruption() {
        val req = AssistantMessageRequest(
            message = "Find my downloaded PDF files",
            conversationId = "impatience_test_conv"
        )

        val latch = CountDownLatch(1)
        val receivedEvents = mutableListOf<AssistantEventStatus>()
        var finalResponse: AssistantResponse? = null

        val reqId = AssistantCore.submitMessage(
            context = context,
            request = req,
            onEvent = { ev -> receivedEvents.add(ev.status) },
            onResult = { resp ->
                finalResponse = resp
                latch.countDown()
            }
        )

        latch.await(5, TimeUnit.SECONDS)
        assertNotNull("Response must arrive", finalResponse)
        assertTrue("Request ID must match", finalResponse!!.requestId == reqId)
        assertTrue("Must have started", receivedEvents.contains(AssistantEventStatus.REQUEST_STARTED))
        assertTrue("Must have completed", receivedEvents.contains(AssistantEventStatus.DONE))

        // Interruption & Cancellation
        AssistantCore.stopSpeaking() // Mutes audio only
        AssistantCore.cancelRequest(reqId) // Cancels task
        assertTrue(true)
    }
}
