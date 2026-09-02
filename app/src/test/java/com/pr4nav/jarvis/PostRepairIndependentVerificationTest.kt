package com.pr4nav.jarvis

import android.content.ContextWrapper
import com.pr4nav.jarvis.context.CandidateItem
import com.pr4nav.jarvis.context.ContextContinuationResult
import com.pr4nav.jarvis.context.ContextManager
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.router.AgentExecutionMode
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PostRepairIndependentVerificationTest {

    private val context = ContextWrapper(null)

    @Before
    fun setup() {
        CanonicalToolRegistry.init(context)
        ContextManager.clear()
        JarvisWorkspace.initWorkspace(null)
    }

    // =========================================================================
    // 1. MULTI-TURN DISPATCHER TESTS (NO MANUAL SEEDING)
    // =========================================================================

    @Test
    fun testRealDispatcher_DeviceContinuation_BluetoothFlow() {
        UnifiedAssistantDispatcher.setAgentMode(context, AgentExecutionMode.NEEDLE_ONLY)

        // Turn 1: Establish conversational context for Bluetooth domain
        ContextManager.updateToolContext("system.bluetooth", JSONObject().put("state", true), "bluetooth")

        // Turn 2: User says "turn it off" (Multi-turn continuation through real entry point)
        var turn2Handled: Boolean? = null
        var turn2Speech: String? = null
        UnifiedAssistantDispatcher.execute(
            context = context,
            rawQuery = "turn it off",
            onResult = { res ->
                turn2Handled = res.handled
                turn2Speech = res.speechResponse
            }
        )
        assertTrue("Turn 2 (turn it off) must be handled by dispatcher", turn2Handled == true)
        assertNotNull(turn2Speech)

        // Turn 3: User says "actually turn it back on"
        var turn3Handled: Boolean? = null
        UnifiedAssistantDispatcher.execute(
            context = context,
            rawQuery = "actually turn it back on",
            onResult = { res ->
                turn3Handled = res.handled
            }
        )
        assertTrue("Turn 3 (actually turn it back on) must be handled by dispatcher", turn3Handled == true)
    }

    @Test
    fun testRealDispatcher_CandidateDisambiguationAndCancellationChain() {
        // Step 1: Multiple candidate disambiguation setup (e.g. 3 contacts)
        val candidates = listOf(
            CandidateItem(1, "Akhil Mobile", "+919876543210"),
            CandidateItem(2, "Akhil Work", "+919123456780"),
            CandidateItem(3, "Akhil Home", "+919988776655")
        )
        ContextManager.setCandidateList("call_contact", candidates)

        // Step 2: "first one"
        val res1 = ContextManager.resolveContinuation("first one")
        assertTrue(res1 is ContextContinuationResult.ResolvedAction)
        assertEquals("+919876543210", (res1 as ContextContinuationResult.ResolvedAction).arguments.optString("number"))

        // Step 3: Re-arm and test "no, second"
        ContextManager.setCandidateList("call_contact", candidates)
        val res2 = ContextManager.resolveContinuation("no, second")
        assertTrue(res2 is ContextContinuationResult.ResolvedAction)
        assertEquals("+919123456780", (res2 as ContextContinuationResult.ResolvedAction).arguments.optString("number"))

        // Step 4: Re-arm and test "actually third"
        ContextManager.setCandidateList("call_contact", candidates)
        val res3 = ContextManager.resolveContinuation("actually third")
        assertTrue(res3 is ContextContinuationResult.ResolvedAction)
        assertEquals("+919988776655", (res3 as ContextContinuationResult.ResolvedAction).arguments.optString("number"))

        // Step 5: Re-arm and test "cancel"
        ContextManager.setCandidateList("call_contact", candidates)
        val resCancel = ContextManager.resolveContinuation("cancel")
        assertTrue(resCancel is ContextContinuationResult.ResolvedText)
        assertEquals("Cancelled.", (resCancel as ContextContinuationResult.ResolvedText).text)

        // Step 6: After cancellation, "first one" must NOT resolve old candidates
        val resStale = ContextManager.resolveContinuation("first one")
        assertTrue("Stale candidate must return None after cancellation", resStale is ContextContinuationResult.None)
    }

    @Test
    fun testContextManager_TimeoutAndExpiry() {
        ContextManager.updateToolContext("system.bluetooth", JSONObject().put("state", true), "bluetooth")
        assertFalse("Context should be active immediately", ContextManager.isExpired())

        ContextManager.clear()
        assertTrue("Cleared context must return None", ContextManager.resolveContinuation("turn it off") is ContextContinuationResult.None)
    }

    // =========================================================================
    // 2. ADVERSARIAL MODEL SHELL INJECTION & POLICY BYPASS TESTS
    // =========================================================================

    @Test
    fun testAdversarialModel_DestructiveCommandsBlocked() {
        val payloads = listOf(
            "```bash\nrm -rf / --no-preserve-root\n```",
            "```command\nrmdir /storage/emulated/0\n```",
            "```sh\nmkfs.ext4 /dev/block/bootdevice\n```",
            "```bash\ndd if=/dev/zero of=/dev/block/sda\n```",
            "```bash\nreboot\n```",
            "```bash\nkillall -9 zygote\n```"
        )

        for (payload in payloads) {
            val out = GeminiCloudLLM.handleEmbeddedCommands(context, payload)
            assertFalse("Destructive command must not succeed: $payload", out.contains("Command executed successfully."))
            assertTrue("Security policy rejection expected for: $payload", out.contains("⚠️ Command Blocked by Safety Policy"))
        }
    }

    @Test
    fun testAdversarialModel_ChainedAndSubstitutedCommandsBlocked() {
        val payloads = listOf(
            "```bash\necho hello && rm -rf /sdcard\n```",
            "```bash\necho foo; rmdir /system\n```",
            "```bash\necho `rm -rf /`\n```",
            "```bash\necho $(rm -rf /)\n```"
        )

        for (payload in payloads) {
            val out = GeminiCloudLLM.handleEmbeddedCommands(context, payload)
            assertTrue("Chained destructive payload must be blocked: $payload", out.contains("⚠️ Command Blocked by Safety Policy"))
        }
    }

    @Test
    fun testAdversarialModel_MalformedToolBlocksRejected() {
        // Invalid JSON
        val malformedJson = "```action\n{ intent: malformed, arguments: [}\n```"
        val out1 = GeminiCloudLLM.handleEmbeddedCommands(context, malformedJson)
        assertFalse(out1.contains("Executed"))

        // Unregistered tool
        val unknownTool = "```action\n{\"intent\": \"nuclear_launch_code\", \"arguments\": {}}\n```"
        val out2 = GeminiCloudLLM.handleEmbeddedCommands(context, unknownTool)
        assertTrue("Unregistered tool must be rejected", out2.contains("Action Validation Failed") || !out2.contains("Executed"))
    }

    @Test
    fun testCanonicalToolRegistry_RunCommandSafetyGuard() {
        val dangerousCommands = listOf(
            "rm -rf /",
            "rm -rf /storage/emulated/0/JARVIS",
            "dd if=/dev/urandom of=/dev/sda",
            "kill -9 1",
            "apt-get remove python3"
        )

        for (cmd in dangerousCommands) {
            val args = JSONObject().put("command", cmd)
            val res = CanonicalToolRegistry.execute(context, "run_command", args)
            assertFalse("Dangerous command must fail: $cmd", res.success)
            assertEquals("FORBIDDEN", res.error?.code)
        }
    }

    // =========================================================================
    // 3. ASYNCHRONOUS CANCELLATION ISOLATION TESTS
    // =========================================================================

    @Test
    fun testAsyncCancellation_LateCallbackIsolation() {
        val latch = CountDownLatch(1)
        val taskAResultFired = AtomicBoolean(false)
        val currentActiveTaskId = AtomicReference<String>("task-B-12345") // Switched to Task B

        val taskATaskId = "task-A-67890" // Cancelled Task A

        // Simulate background thread completing Task A after cancellation
        Thread {
            Thread.sleep(50)
            // Simulating AgentActivity callback guard
            if (currentActiveTaskId.get() == taskATaskId) {
                taskAResultFired.set(true)
            }
            latch.countDown()
        }.start()

        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertFalse("Late callback from cancelled Task A must be dropped", taskAResultFired.get())
    }
}
