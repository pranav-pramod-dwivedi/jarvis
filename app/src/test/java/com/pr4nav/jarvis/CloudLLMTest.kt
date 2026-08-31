package com.pr4nav.jarvis

import com.pr4nav.jarvis.llm.GeminiCloudLLM
import org.junit.Assert.*
import org.junit.Test

class CloudLLMTest {

    @Test
    fun testCleanForSpeech() {
        val markdownText = "**Hello**! Here is a list:\n* Point 1\n* Point 2\nCheck out `code` and [link](https://example.com)."
        val speech = GeminiCloudLLM.cleanForSpeech(markdownText)

        assertFalse("Markdown asterisks should be stripped", speech.contains("*"))
        assertFalse("Markdown backticks should be stripped", speech.contains("`"))
        assertFalse("Raw URLs should be stripped", speech.contains("https://example.com"))
        assertTrue("Text content should be preserved", speech.contains("Hello"))
        assertTrue("Point 1 preserved", speech.contains("Point 1"))
    }

    @Test
    fun testSpeechFormattingHeadings() {
        val markdownHeadings = "### Heading Title\nSome explanatory text here."
        val cleaned = GeminiCloudLLM.cleanForSpeech(markdownHeadings)
        assertFalse("Hash signs stripped", cleaned.contains("#"))
        assertTrue("Heading Title preserved", cleaned.contains("Heading Title"))
        assertTrue("Body preserved", cleaned.contains("Some explanatory text here."))
    }
}
