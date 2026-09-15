package com.pr4nav.jarvis.llm

import org.junit.Assert.*
import org.junit.Test

/** Brutal tests for Ollama Cloud parsing (no network). */
class OllamaClientTest {

    @Test fun parseTags() {
        val raw = """{"models":[
            {"name":"qwen3:30b-a3b","size":19000000000},
            {"name":"gpt-oss:120b"},
            {"name":"  "},
            {"notname":"x"},
            {"name":"null"}
        ]}"""
        assertEquals(listOf("qwen3:30b-a3b", "gpt-oss:120b"), OllamaClient.parseTagsResponse(raw))
    }

    @Test fun parseTagsHostile() {
        assertTrue(OllamaClient.parseTagsResponse("").isEmpty())
        assertTrue(OllamaClient.parseTagsResponse("not json").isEmpty())
        assertTrue(OllamaClient.parseTagsResponse("{}").isEmpty())
        assertTrue(OllamaClient.parseTagsResponse("[]").isEmpty())
        assertTrue(OllamaClient.parseTagsResponse("{\"models\":[]}").isEmpty())
        assertTrue(OllamaClient.parseTagsResponse("{\"models\":null}").isEmpty())
    }

    @Test fun parseChat() {
        val (content, thinking) = OllamaClient.parseChatResponse(
            """{"model":"qwen3:30b","message":{"role":"assistant","content":"Hello there"},"done":true}"""
        )
        assertEquals("Hello there", content)
        assertEquals("", thinking)
    }

    @Test fun parseChatWithThinking() {
        val (content, thinking) = OllamaClient.parseChatResponse(
            """{"message":{"role":"assistant","content":"Answer","thinking":"Let me think"}}"""
        )
        assertEquals("Answer", content)
        assertEquals("Let me think", thinking)
    }

    @Test fun parseChatHostile() {
        assertEquals("" to "", OllamaClient.parseChatResponse(""))
        assertEquals("" to "", OllamaClient.parseChatResponse("garbage"))
        assertEquals("" to "", OllamaClient.parseChatResponse("{}"))
        assertEquals("" to "", OllamaClient.parseChatResponse("{\"message\":null}"))
        val (c, _) = OllamaClient.parseChatResponse("{\"message\":{\"content\":\"  \"}}")
        assertEquals("", c)
    }

    @Test fun defaultsSane() {
        assertTrue(OllamaClient.DEFAULT_MODEL.isNotBlank())
        assertTrue(OllamaClient.OLLAMA_CHAT_ENDPOINT.startsWith("https://"))
        assertTrue(OllamaClient.OLLAMA_TAGS_ENDPOINT.startsWith("https://"))
        assertEquals(OllamaClient.DEFAULT_MODEL, OllamaClient.getModel(null))
        assertEquals("", OllamaClient.getApiKey(null))
    }
}
