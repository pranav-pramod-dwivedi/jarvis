package com.pr4nav.jarvis

import com.pr4nav.jarvis.llm.DefaultLocalLLM
import com.pr4nav.jarvis.llm.LLMState
import com.pr4nav.jarvis.llm.LocalModelManager
import com.pr4nav.jarvis.needle.NeedleToolCatalog
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class LocalLLMTest {

    @Test
    fun testLocalModelManagerCatalogAndSpecs() {
        val models = LocalModelManager.AVAILABLE_MODELS
        assertTrue("Available models list should not be empty", models.isNotEmpty())

        val qwen15b = models.firstOrNull { it.id == "qwen2.5-1.5b-instruct-q4" }
        assertNotNull("Qwen 2.5 1.5B spec should be defined", qwen15b)
        assertEquals("GGUF Q4_K_M", qwen15b?.quantFormat)
        assertTrue("URL must point to GGUF weights", qwen15b!!.downloadUrl.endsWith(".gguf"))
        assertTrue("Estimated size must be reasonable", qwen15b.estimatedSizeBytes > 500_000_000L)
    }

    @Test
    fun testFewShotPromptTemplateFormatting() {
        val toolsJson = NeedleToolCatalog.generateSchemasJson()
        assertTrue("Tools JSON must be valid", toolsJson.isNotBlank())

        val prompt = LocalModelManager.buildPromptTemplate("Call Akhil", toolsJson)
        assertTrue("Must contain system marker", prompt.contains("<|im_start|>system"))
        assertTrue("Must contain user marker", prompt.contains("<|im_start|>user"))
        assertTrue("Must contain prompt", prompt.contains("Call Akhil"))
        assertTrue("Must contain assistant tag", prompt.endsWith("<|im_start|>assistant"))
    }

    @Test
    fun testDefaultLocalLLMContract() {
        val llm = DefaultLocalLLM()
        val status = llm.status()
        assertEquals(LLMState.NOT_LOADED, status.state)

        val loadFuture = llm.load()
        assertTrue(loadFuture.get(2, TimeUnit.SECONDS))
        assertEquals(LLMState.READY, llm.status().state)

        // Generate response with 30s max timeout contract
        val future = llm.generate("Check battery percentage", 5_000L)
        val result = future.get(5, TimeUnit.SECONDS)
        assertNotNull(result)
        assertEquals("system.battery", result.toolCall)
        assertTrue(result.confidence > 0.8f)

        llm.unload()
        assertEquals(LLMState.NOT_LOADED, llm.status().state)
    }
}
