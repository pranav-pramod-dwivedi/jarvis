package com.pr4nav.jarvis

import com.pr4nav.jarvis.response.JarvisResponse
import com.pr4nav.jarvis.response.UserResponseSanitizer
import org.junit.Assert.*
import org.junit.Test

class UserResponseSanitizerTest {

    @Test
    fun testDetectsRawJsonAndInternalStructures() {
        val raw1 = "{\"type\":\"intent\",\"category\":\"INFORMATION\",\"intent\":\"search_web\",\"confidence\":0.95}"
        assertTrue(UserResponseSanitizer.isRawJsonOrInternalStructure(raw1))

        val raw2 = "{\"type\":\"action\",\"intent\":\"system.volume\",\"confidence\":1}"
        assertTrue(UserResponseSanitizer.isRawJsonOrInternalStructure(raw2))

        val raw3 = "{\"type\":\"action\",\"intent\":\"system.torch\",\"arguments\":{\"state\":true}}"
        assertTrue(UserResponseSanitizer.isRawJsonOrInternalStructure(raw3))

        val raw4 = "Narendra Modi is the Prime Minister of India."
        assertFalse(UserResponseSanitizer.isRawJsonOrInternalStructure(raw4))

        val raw5 = "Flashlight turned on."
        assertFalse(UserResponseSanitizer.isRawJsonOrInternalStructure(raw5))
    }

    @Test
    fun testSanitizesInformationModelOutput() {
        val raw = "{\"type\":\"intent\",\"category\":\"INFORMATION\",\"intent\":\"search_web\",\"confidence\":0.95}"
        val sanitized = UserResponseSanitizer.sanitize(raw, "Who is Modi?")

        assertTrue("Must contain Prime Minister of India", sanitized.contains("Prime Minister of India"))
        assertFalse("Must NOT contain raw json", sanitized.contains("{") || sanitized.contains("}"))
        assertFalse("Must NOT contain search_web", sanitized.contains("search_web"))
        assertFalse("Must NOT contain 0.95", sanitized.contains("0.95"))
    }

    @Test
    fun testSanitizesActionModelOutput() {
        // Torch on
        val torchOnRaw = "{\"type\":\"action\",\"intent\":\"system.torch\",\"arguments\":{\"state\":true}}"
        val torchOnRes = UserResponseSanitizer.sanitize(torchOnRaw, "turn on flashlight")
        assertEquals("Flashlight turned on.", torchOnRes)

        // Torch off
        val torchOffRaw = "{\"type\":\"action\",\"intent\":\"system.torch\",\"arguments\":{\"state\":false}}"
        val torchOffRes = UserResponseSanitizer.sanitize(torchOffRaw, "turn off flashlight")
        assertEquals("Flashlight turned off.", torchOffRes)

        // Volume
        val volRaw = "{\"type\":\"action\",\"intent\":\"system.volume\",\"arguments\":{\"action\":\"raise\"}}"
        val volRes = UserResponseSanitizer.sanitize(volRaw, "volume up")
        assertEquals("Volume increased.", volRes)
    }

    @Test
    fun testSanitizesForTtsSpeech() {
        val textWithMarkdown = "Here is the **bold answer** with `inline code` and emojis: ⚡🚀"
        val speech = UserResponseSanitizer.sanitizeForSpeech(textWithMarkdown)

        assertFalse("Must not contain markdown asterisks", speech.contains("*"))
        assertFalse("Must not contain markdown backticks", speech.contains("`"))
        assertEquals("Here is the bold answer with inline code and emojis:", speech.trim())
    }

    @Test
    fun testSanitizesIdentityLeaksAndBoilerplate() {
        val qwenLeak = "I am Qwen, a large language model trained by Alibaba."
        val qwenCleaned = UserResponseSanitizer.sanitize(qwenLeak)
        assertFalse("Must NOT mention Qwen", qwenCleaned.contains("Qwen"))
        assertFalse("Must NOT mention Alibaba", qwenCleaned.contains("Alibaba"))
        assertTrue("Must assert JARVIS identity", qwenCleaned.contains("JARVIS"))

        val geminiLeak = "I am Gemini, an AI language model developed by Google. How can I help you today?"
        val geminiCleaned = UserResponseSanitizer.sanitize(geminiLeak)
        assertFalse("Must NOT mention Gemini", geminiCleaned.contains("Gemini"))
        assertFalse("Must NOT mention Google", geminiCleaned.contains("Google"))
        assertTrue("Must assert JARVIS identity", geminiCleaned.contains("JARVIS"))

        val boilerplate1 = "Certainly! The capital of France is Paris."
        val cleaned1 = UserResponseSanitizer.sanitize(boilerplate1)
        assertEquals("The capital of France is Paris.", cleaned1)

        val boilerplate2 = "As an AI language model, photosynthesis is the process used by plants."
        val cleaned2 = UserResponseSanitizer.sanitize(boilerplate2)
        assertEquals("Photosynthesis is the process used by plants.", cleaned2)
    }

    @Test
    fun testJarvisResponseConstruction() {
        val rawJson = "{\"type\":\"intent\",\"category\":\"INFORMATION\",\"intent\":\"search_web\",\"confidence\":0.95}"
        val resp = JarvisResponse.of(rawJson, query = "Who is Narendra Modi?")

        assertTrue(resp.text.contains("Prime Minister of India"))
        assertFalse(resp.text.contains("{"))
        assertFalse(resp.speechText.contains("*"))
        assertEquals(com.pr4nav.jarvis.response.TerminationStatus.FINAL_ANSWER, resp.status)
        assertFalse(resp.isError)
    }

    @Test
    fun testStripThinking() {
        // Standard closed think tag
        val standard = "<think>Let me reason about this.\n2+2 is 4.</think>The result is 4."
        val (trace1, text1) = UserResponseSanitizer.stripThinking(standard)
        assertEquals("The result is 4.", text1)
        assertTrue(trace1.contains("2+2 is 4."))

        // Alternate tags
        val altTags = "<thought>Thinking...</thought><reasoning>Deep dive</reasoning>Done successfully."
        val (_, text2) = UserResponseSanitizer.stripThinking(altTags)
        assertEquals("Done successfully.", text2)

        // Unclosed think tag
        val unclosed = "<think>Calculating in progress..."
        val (trace3, text3) = UserResponseSanitizer.stripThinking(unclosed)
        assertTrue(trace3.contains("Calculating in progress"))

        // Answer trapped inside think tags with empty outer body
        val trapped = "<think>The user wants to know the speed of light.\nTherefore, the speed of light is approximately 299,792,458 m/s.</think>"
        val (_, text4) = UserResponseSanitizer.stripThinking(trapped)
        assertTrue("Must extract answer from thinking", text4.contains("299,792,458"))
    }
}
