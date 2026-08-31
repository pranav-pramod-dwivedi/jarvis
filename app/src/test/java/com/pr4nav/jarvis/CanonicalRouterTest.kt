package com.pr4nav.jarvis

import android.content.Context
import com.pr4nav.jarvis.llm.LLMResult
import com.pr4nav.jarvis.llm.LLMStatus
import com.pr4nav.jarvis.llm.LocalLLM
import com.pr4nav.jarvis.router.CanonicalRouter
import com.pr4nav.jarvis.router.RouterTier
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture

class CanonicalRouterTest {

    private val mockContext = android.content.ContextWrapper(null)

    @Before
    fun setup() {
        CanonicalToolRegistry.init(mockContext)
        CanonicalToolRegistry.register(CanonicalToolDef(
            name = "open_app",
            description = "Opens an app",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().put("app", JSONObject().put("type", "string")))
                put("required", JSONArray().put("app"))
            },
            execute = { _, args -> ToolResult.ok("Launched " + args.optString("app")) }
        ))
    }

    @Test
    fun testTier1DeterministicRouting() {
        val router = CanonicalRouter()
        val decision = router.route(mockContext, "open chrome")
        assertEquals(RouterTier.DETERMINISTIC_NEEDLE, decision.tier)
        assertEquals("open_app", decision.tool)
        assertTrue(decision.confidence >= 0.85f)
        assertNotNull(decision.executionResult)
        assertTrue(decision.executionResult!!.success)
    }

    @Test
    fun testTier2LocalLLMRouting() {
        val mockLLM = object : LocalLLM {
            override val name: String = "Mock-LLM"
            override fun isAvailable(): Boolean = true
            override fun load(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
            override fun unload(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
            override fun cancel() {}
            override fun status(): LLMStatus = LLMStatus(com.pr4nav.jarvis.llm.LLMState.READY)
            override fun generate(prompt: String, timeoutMs: Long): CompletableFuture<LLMResult> {
                val res = LLMResult(
                    rawText = "parsed",
                    toolCall = "open_app",
                    args = JSONObject().put("app", "Slack"),
                    confidence = 0.88f
                )
                return CompletableFuture.completedFuture(res)
            }
        }

        val router = CanonicalRouter(localLLM = mockLLM)
        // A complex phrasing not covered by Tier 1 regexes
        val decision = router.route(mockContext, "could you please fire up the workspace communicator app")
        assertEquals(RouterTier.LOCAL_LLM_NEEDLE, decision.tier)
        assertEquals("open_app", decision.tool)
        assertEquals("Slack", decision.arguments?.getString("app"))
        assertTrue(decision.confidence >= 0.60f)
        assertNotNull(decision.executionResult)
        assertTrue(decision.executionResult?.success == true)
    }

    @Test
    fun testTier3CloudEscalationOnLowConfidence() {
        val lowConfLLM = object : LocalLLM {
            override val name: String = "Low-Conf-LLM"
            override fun isAvailable(): Boolean = true
            override fun load(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
            override fun unload(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
            override fun cancel() {}
            override fun status(): LLMStatus = LLMStatus(com.pr4nav.jarvis.llm.LLMState.READY)
            override fun generate(prompt: String, timeoutMs: Long): CompletableFuture<LLMResult> {
                // Confidence below 0.60 threshold
                val res = LLMResult(
                    rawText = "uncertain",
                    toolCall = "open_app",
                    args = JSONObject().put("app", "Slack"),
                    confidence = 0.45f
                )
                return CompletableFuture.completedFuture(res)
            }
        }

        val router = CanonicalRouter(localLLM = lowConfLLM)
        val decision = router.route(mockContext, "how do quantum computers work and explain superdeterminism")
        assertEquals(RouterTier.CLOUD_ESCALATION, decision.tier)
        assertNull(decision.tool)
    }

    @Test
    fun testTier3CloudEscalationWhenNoLocalModelAvailable() {
        val noLLM = object : LocalLLM {
            override val name: String = "Unavailable-LLM"
            override fun isAvailable(): Boolean = false
            override fun load(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)
            override fun unload(): CompletableFuture<Boolean> = CompletableFuture.completedFuture(false)
            override fun cancel() {}
            override fun status(): LLMStatus = LLMStatus(com.pr4nav.jarvis.llm.LLMState.NOT_LOADED)
            override fun generate(prompt: String, timeoutMs: Long): CompletableFuture<LLMResult> =
                CompletableFuture.failedFuture(IllegalStateException("No model"))
        }

        val router = CanonicalRouter(localLLM = noLLM)
        val decision = router.route(mockContext, "write me a resume in LaTeX")
        assertEquals(RouterTier.CLOUD_ESCALATION, decision.tier)
    }
}
