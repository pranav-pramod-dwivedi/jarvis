package com.pr4nav.jarvis

import com.pr4nav.jarvis.engine.EngineType
import com.pr4nav.jarvis.engine.QwenLocalInferenceEngine
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QwenDirectInferenceTest {

    private val mockContext = android.content.ContextWrapper(null)

    @Before
    fun setup() {
        CanonicalToolRegistry.init(null)
    }

    @Test
    fun testQwenIdentityVerification() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.infer("QWEN_ENGINE_TEST_73921")
        assertEquals(EngineType.QWEN_LOCAL, res.metadata.requestedEngine)
        assertEquals(EngineType.QWEN_LOCAL, res.metadata.actualEngine)
        assertTrue(res.metadata.isRoutingIntegrityValid)
    }

    @Test
    fun testSayExactlyQwenOk() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.infer("Say exactly:\nQWEN_OK")
        assertEquals("QWEN_OK", res.rawOutput)
        assertEquals("TEST_ECHO", res.intent)
    }

    @Test
    fun testWhatIs2Plus2() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.infer("What is 2 + 2?")
        assertEquals("CONVERSATION_ANSWER", res.intent)
        assertTrue(res.rawOutput.contains("2+2=4") || res.rawOutput.contains("2 + 2 = 4"))
    }

    @Test
    fun testTorchChaluKar() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.infer("Torch chalu kar")
        assertEquals("system.torch", res.intent)
        assertTrue(res.arguments?.optBoolean("state") == true)
    }

    @Test
    fun testWhoIsModi() {
        val engine = QwenLocalInferenceEngine(mockContext)
        val res = engine.infer("Who is Modi?")
        assertEquals("search_web", res.intent)
    }
}
