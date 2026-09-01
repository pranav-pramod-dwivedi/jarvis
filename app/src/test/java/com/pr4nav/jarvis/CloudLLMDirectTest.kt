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
    fun testSpeechCleaningForTTS() {
        val raw = "```command\necho test\n```\n**Turning on** the *flashlight*! Check https://example.com."
        val cleaned = GeminiCloudLLM.cleanForSpeech(raw)
        assertFalse("Cleaned speech must not contain markdown asterisks", cleaned.contains("*"))
        assertFalse("Cleaned speech must not contain URLs", cleaned.contains("https://"))
        assertFalse("Cleaned speech must not contain code backticks", cleaned.contains("```"))
    }
}
