package com.pr4nav.jarvis

import com.pr4nav.jarvis.intent.IntentCategory
import com.pr4nav.jarvis.intent.IntentClassifier
import com.pr4nav.jarvis.intent.ResponseType
import com.pr4nav.jarvis.router.LanguageNormalizer
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolSemanticContracts
import com.pr4nav.jarvis.tools.ToolValidator
import com.pr4nav.jarvis.tools.ValidationResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoutingRegressionSuiteTest {

    @Before
    fun setup() {
        CanonicalToolRegistry.init(null)
    }

    @Test
    fun testDirectDeterministicOverrides() {
        // 1. Flashlight On
        val r1 = LanguageNormalizer.normalize("turn on flashlight")
        assertNotNull("turn on flashlight must match deterministically", r1)
        assertEquals("system.torch", r1?.tool)
        assertTrue(r1?.args?.getBoolean("state") == true)

        // 2. Flashlight Off
        val r2 = LanguageNormalizer.normalize("turn off flashlight")
        assertNotNull(r2)
        assertEquals("system.torch", r2?.tool)
        assertFalse(r2?.args?.getBoolean("state") == true)

        // 3. Torch chalu kar (Hinglish)
        val r3 = LanguageNormalizer.normalize("torch chalu kar")
        assertNotNull(r3)
        assertEquals("system.torch", r3?.tool)
        assertTrue(r3?.args?.getBoolean("state") == true)

        // 4. Open App
        val r4 = LanguageNormalizer.normalize("open instagram")
        assertNotNull(r4)
        assertEquals("open_app", r4?.tool)
        assertEquals("instagram", r4?.args?.getString("app")?.lowercase())

        // 5. Navigate
        val r5 = LanguageNormalizer.normalize("take me home")
        assertNotNull(r5)
        assertEquals("navigate", r5?.tool)
        assertEquals("home", r5?.args?.getString("destination"))

        // 6. Settings
        val r6 = LanguageNormalizer.normalize("wifi settings kholo")
        assertNotNull(r6)
        assertEquals("open_settings", r6?.tool)
        assertEquals("wifi", r6?.args?.getString("subpage"))
    }

    @Test
    fun testMultilingualTorchDirectMatches() {
        val enablePrompts = listOf(
            "Torch chalu kar",
            "Torch jala do",
            "Torch on kar",
            "Flashlight chalu kar",
            "Phone torch on karo",
            "Mobile torch jala do",
            "Turn on flashlight",
            "Turn on torch",
            "Flashlight on kar"
        )

        for (p in enablePrompts) {
            val res = LanguageNormalizer.normalize(p)
            assertNotNull("Prompt '$p' must match deterministically", res)
            assertEquals("Prompt '$p' must map to system.torch", "system.torch", res?.tool)
            assertTrue("Prompt '$p' must have state=true", res?.args?.getBoolean("state") == true)
            assertEquals("Prompt '$p' must have confidence 1.0", 1.0f, res?.confidence ?: 0f, 0.01f)
        }

        val disablePrompts = listOf(
            "Torch band kar",
            "Torch bujha do",
            "Torch off kar",
            "Flashlight band kar",
            "Phone torch band karo",
            "Mobile torch bujha do",
            "Turn off flashlight",
            "Turn off torch",
            "Flashlight off kar",
            "Torch bandh kar yaar"
        )

        for (p in disablePrompts) {
            val res = LanguageNormalizer.normalize(p)
            assertNotNull("Prompt '$p' must match deterministically", res)
            assertEquals("Prompt '$p' must map to system.torch", "system.torch", res?.tool)
            assertFalse("Prompt '$p' must have state=false", res?.args?.getBoolean("state") == true)
            assertEquals("Prompt '$p' must have confidence 1.0", 1.0f, res?.confidence ?: 0f, 0.01f)
        }
    }

    @Test
    fun testInformationQueryRejectsMediaPlay() {
        val query = "Who is Narendra Modi"
        val classified = IntentClassifier.classify(query)
        assertEquals(IntentCategory.INFORMATION, classified.category)
        assertEquals(ResponseType.ANSWER, classified.responseType)

        // Simulate Qwen proposing media.play with {"query": "narendra modi"}
        val proposedArgs = JSONObject().put("query", "narendra modi")
        val validation = ToolValidator.validate(null, "media.play", proposedArgs, query)

        assertTrue("media.play MUST be rejected for informational question", validation is ValidationResult.Rejected)
        val rejection = validation as ValidationResult.Rejected
        assertEquals("SEMANTIC_MISMATCH", rejection.reasonCode)
    }

    @Test
    fun testMathCalculationRejectsMediaPlay() {
        val query = "What is 2 + 2?"
        val classified = IntentClassifier.classify(query)
        assertEquals(IntentCategory.CONVERSATION, classified.category)
        assertEquals(ResponseType.ANSWER, classified.responseType)
        assertEquals("2+2=4", classified.directAnswer?.replace(" ", ""))

        val proposedArgs = JSONObject().put("query", "2 + 2")
        val validation = ToolValidator.validate(null, "media.play", proposedArgs, query)
        assertTrue("media.play MUST be rejected for math query", validation is ValidationResult.Rejected)
    }

    @Test
    fun testCodingQueryClassification() {
        val query = "Explain why this Kotlin code crashes with NullPointerException"
        val classified = IntentClassifier.classify(query)
        assertEquals(IntentCategory.CODING, classified.category)
        assertEquals(ResponseType.ANSWER, classified.responseType)
    }

    @Test
    fun testMediaPlayAcceptsValidMusicPrompts() {
        val query = "Play Believer"
        val classified = IntentClassifier.classify(query)
        assertEquals(IntentCategory.MEDIA, classified.category)
        assertEquals(ResponseType.ACTION, classified.responseType)

        val proposedArgs = JSONObject().put("query", "Believer")
        val validation = ToolValidator.validate(null, "media.play", proposedArgs, query)
        assertTrue("media.play must be valid for music prompt", validation is ValidationResult.Valid)
    }

    @Test
    fun testWebSearchAcceptsInformationalQueries() {
        val query = "Search for Narendra Modi"
        val proposedArgs = JSONObject().put("query", "Narendra Modi")
        val validation = ToolValidator.validate(null, "search_web", proposedArgs, query)
        assertTrue("search_web must be valid for search prompt", validation is ValidationResult.Valid)
    }

    @Test
    fun testLocalQwenInformationPipelineProducesRealAnswerNotJson() {
        // 1. Who is Modi?
        val query1 = "Who is Modi?"
        val classified1 = IntentClassifier.classify(query1)
        assertEquals(IntentCategory.INFORMATION, classified1.category)

        val mode1 = com.pr4nav.jarvis.response.AnswerSynthesizer.determineResponseMode(query1, classified1.category.name)
        assertEquals(com.pr4nav.jarvis.response.ResponseMode.SEARCH_THEN_ANSWER, mode1)

        val validation1 = ToolValidator.validate(null, "search_web", JSONObject().put("query", "Modi"), query1)
        assertTrue(validation1 is ValidationResult.Valid)

        val synthesized1 = com.pr4nav.jarvis.response.AnswerSynthesizer.synthesize(
            originalQuery = query1,
            toolName = "search_web",
            toolData = JSONObject().put("action", "WEB_SEARCH_OPENED").put("query", "Modi"),
            responseMode = mode1
        )

        assertTrue("Synthesized answer must contain Prime Minister of India", synthesized1.contains("Prime Minister of India"))
        assertFalse("Synthesized answer must not leak tool name", synthesized1.contains("search_web"))
        assertFalse("Synthesized answer must not leak validation score", synthesized1.contains("100/100"))
        assertFalse("Synthesized answer must not leak raw JSON brackets", synthesized1.contains("{") || synthesized1.contains("}"))

        // 2. Who is Narendra Modi?
        val query2 = "Who is Narendra Modi?"
        val synthesized2 = com.pr4nav.jarvis.response.AnswerSynthesizer.synthesize(
            originalQuery = query2,
            toolName = "search_web",
            toolData = JSONObject().put("action", "WEB_SEARCH_OPENED"),
            responseMode = com.pr4nav.jarvis.response.ResponseMode.SEARCH_THEN_ANSWER
        )
        assertTrue(synthesized2.contains("Prime Minister of India"))
        assertFalse(synthesized2.contains("{") || synthesized2.contains("}"))

        // 3. What is 2 + 2?
        val mathAns = com.pr4nav.jarvis.response.AnswerSynthesizer.synthesize("What is 2 + 2?", "calculator", JSONObject(), com.pr4nav.jarvis.response.ResponseMode.ANSWER)
        assertEquals("2 + 2 = 4", mathAns)
    }
}
