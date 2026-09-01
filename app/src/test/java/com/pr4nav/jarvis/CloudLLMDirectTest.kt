package com.pr4nav.jarvis

import com.pr4nav.jarvis.engine.EngineType
import com.pr4nav.jarvis.engine.NeedleInferenceEngine
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import org.junit.Assert.*
import org.junit.Test

class CloudLLMDirectTest {

    private val mockContext = android.content.ContextWrapper(null)

    @Test
    fun testCloudLLMSystemInstructionMandatesZeroRefusal() {
        val instruction = GeminiCloudLLM.DEFAULT_SYSTEM_INSTRUCTION
        assertTrue("Must mandate never denying user", instruction.contains("NEVER deny"))
        assertTrue("Must include shell command capability", instruction.contains("```command"))
        assertTrue("Must include action tool capability", instruction.contains("```action"))
        assertTrue("Must list device tools", instruction.contains("system.torch"))
    }

    @Test
    fun testNeedleInferenceEngineDirectReflex() {
        val engine = NeedleInferenceEngine(mockContext)
        val res = engine.infer("Turn on flashlight")
        assertEquals(EngineType.NEEDLE_REFLEX, res.metadata.actualEngine)
        assertEquals("system.torch", res.intent)
        assertTrue(res.success)
    }

    @Test
    fun testAgentExecutionModes() {
        val modes = com.pr4nav.jarvis.router.AgentExecutionMode.values()
        assertEquals(3, modes.size)
        assertTrue(modes.contains(com.pr4nav.jarvis.router.AgentExecutionMode.CLOUD_NEEDLE))
        assertTrue(modes.contains(com.pr4nav.jarvis.router.AgentExecutionMode.NEEDLE_ONLY))
        assertTrue(modes.contains(com.pr4nav.jarvis.router.AgentExecutionMode.QWEN_NEEDLE))
    }

    @Test
    fun testQwenAgentClientDefaults() {
        assertEquals("http://127.0.0.1:8080", com.pr4nav.jarvis.llm.QwenAgentClient.DEFAULT_AGENT_URL)
    }
}
