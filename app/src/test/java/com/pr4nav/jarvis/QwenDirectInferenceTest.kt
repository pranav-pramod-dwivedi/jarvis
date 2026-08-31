package com.pr4nav.jarvis

import com.pr4nav.jarvis.engine.EngineType
import com.pr4nav.jarvis.engine.QwenLocalInferenceEngine
import org.junit.Assert.*
import org.junit.Test

class QwenDirectInferenceTest {

    private val mockContext = android.content.ContextWrapper(null)

    @Test
    fun testQwenProvenanceTrace() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Hello, who are you?")
        assertEquals(EngineType.QWEN_LOCAL, res.metadata.requestedEngine)
        assertEquals(EngineType.QWEN_LOCAL, res.metadata.actualEngine)
        assertTrue(res.metadata.isRoutingIntegrityValid)
        assertEquals("RAW_QWEN_CHAT", res.metadata.provenanceTrace.promptSource)
        assertEquals("DISABLED", res.metadata.provenanceTrace.needle)
        assertEquals("DISABLED", res.metadata.provenanceTrace.agy)
        assertEquals("DISABLED", res.metadata.provenanceTrace.cloud)
        assertEquals("DISABLED", res.metadata.provenanceTrace.toolRouter)
    }

    @Test
    fun testSayExactlyQwenOk() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Say exactly: QWEN_OK")
        assertEquals("QWEN_OK", res.rawOutput.trim())
    }

    @Test
    fun testRawWhatIs2Plus2() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("What is 2 + 2?")
        assertEquals("2 + 2 = 4.", res.rawOutput.trim())
        assertFalse("Raw Qwen output must not contain JSON", res.rawOutput.contains("{") || res.rawOutput.contains("}"))
        assertFalse("Raw Qwen output must not leak intent keys", res.rawOutput.contains("\"intent\":"))
    }

    @Test
    fun testRawExplainKotlinCrash() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Explain what a Kotlin crash is.")
        assertTrue("Output should explain runtime exceptions", res.rawOutput.contains("exception") || res.rawOutput.contains("crash"))
        assertFalse(res.rawOutput.contains("{"))
    }

    @Test
    fun testRawTellJoke() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Tell me a short joke.")
        assertTrue(res.rawOutput.contains("light") || res.rawOutput.contains("bugs") || res.rawOutput.contains("joke"))
        assertFalse(res.rawOutput.contains("{"))
    }

    @Test
    fun testRawWhoIsModi() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Who is Narendra Modi?")
        assertTrue(res.rawOutput.contains("Prime Minister of India"))
        assertFalse(res.rawOutput.contains("{"))
    }

    @Test
    fun testRawWhatIsAndroid() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("What is Android?")
        assertTrue(res.rawOutput.contains("operating system") || res.rawOutput.contains("Linux"))
        assertFalse(res.rawOutput.contains("{"))
    }

    @Test
    fun testRawWhySkyIsBlue() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Why is the sky blue?")
        assertTrue(res.rawOutput.contains("scattering") || res.rawOutput.contains("Rayleigh") || res.rawOutput.contains("wavelengths"))
        assertFalse(res.rawOutput.contains("{"))
    }

    @Test
    fun testRawWriteCalculatorInKotlin() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Write a simple calculator in Kotlin.")
        assertTrue(res.rawOutput.contains("fun calculate") || res.rawOutput.contains("kotlin"))
        assertFalse(res.rawOutput.contains("\"intent\":"))
    }

    @Test
    fun testRawWhySkyIsRed() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Why is the sky red?")
        assertTrue("Must explain Rayleigh scattering / sunset", res.rawOutput.contains("Rayleigh") || res.rawOutput.contains("scattering") || res.rawOutput.contains("wavelengths") || res.rawOutput.contains("sunset"))
        assertFalse("Must not contain JSON", res.rawOutput.contains("{"))
        assertFalse("Must not contain default fallback", res.rawOutput.contains("I am processing your query locally"))

        // Verify physical positive metrics
        assertTrue("Prefill tok/s must be positive", res.prefillTokPerSec > 0.0)
        assertTrue("Decode tok/s must be positive", res.decodeTokPerSec > 0.0)
        assertTrue("TTFT must be positive", res.ttftMs > 0)
        assertTrue("Generated tokens must be positive", res.generatedTokens > 0)
    }

    @Test
    fun testRawExplainSunsetInThreeSentences() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateChat("Explain why the sky can appear red at sunset in three sentences.")
        assertTrue(res.rawOutput.contains("sunset") || res.rawOutput.contains("scattering"))
        assertTrue("Generated tokens must be substantial", res.generatedTokens >= 10)
        assertFalse(res.rawOutput.contains("{"))
    }

    @Test
    fun testExplicitToolModeProvidesJson() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.generateToolIntent("Torch chalu kar")
        assertEquals("system.torch", res.intent)
        assertTrue(res.arguments?.optBoolean("state") == true)
        assertTrue(res.rawOutput.contains("\"intent\":"))
    }
}
