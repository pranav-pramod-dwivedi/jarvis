package com.pr4nav.jarvis.router

import org.junit.Assert.*
import org.junit.Test

/** Offline brain: widened matching gate + cloud diagnosis. No network. */
class LocalBrainTest {

    @Test fun gateThreshold() {
        assertTrue(LocalEngine.acceptsLocalMatch(NormalizedToolCall("torch", org.json.JSONObject(), 0.90f)))
        assertTrue(LocalEngine.acceptsLocalMatch(NormalizedToolCall("torch", org.json.JSONObject(), 0.55f)))
        assertTrue(LocalEngine.acceptsLocalMatch(NormalizedToolCall("torch", org.json.JSONObject(), 0.61f)))
        assertFalse(LocalEngine.acceptsLocalMatch(NormalizedToolCall("torch", org.json.JSONObject(), 0.54f)))
        assertFalse(LocalEngine.acceptsLocalMatch(NormalizedToolCall("torch", org.json.JSONObject(), 0.10f)))
        assertFalse(LocalEngine.acceptsLocalMatch(null))
    }

    @Test fun commonActionsMatch() {
        val cases = listOf(
            "turn on flashlight",
            "flashlight on",
            "torch chalu kar",
            "open youtube",
            "take a screenshot",
            "call mom",
            "set volume to 50",
            "turn on wifi",
            "what is the battery percentage"
        )
        var matched = 0
        for (q in cases) {
            val norm = try {
                LanguageNormalizer.normalize(q)
            } catch (_: Exception) {
                null
            }
            if (LocalEngine.acceptsLocalMatch(norm)) matched++
        }
        // At least most everyday phrasings must clear the widened gate
        // (dispatcher Tier-1 at 0.90 already takes the crisp ones).
        assertTrue("only $matched/${cases.size} matched: $cases", matched >= cases.size - 3)
    }

    @Test fun batteryQueryMatches() {
        val norm = LanguageNormalizer.normalize("what is the battery percentage")
        println("battery norm: tool=${norm?.tool} conf=${norm?.confidence} phrase=${norm?.matchedPhrase}")
        assertTrue(LocalEngine.acceptsLocalMatch(norm))
    }

    @Test fun garbageNeverMatches() {
        val junk = listOf(
            "flibberty gibbet zorpt",
            "asdfghjkl qwerty",
            "😂🔥👍",
            "",
            "   ",
            "the philosophical implications of consciousness"
        )
        for (q in junk) {
            val norm = try {
                LanguageNormalizer.normalize(q)
            } catch (_: Exception) {
                null
            }
            assertFalse("junk matched: [$q] -> $norm", LocalEngine.acceptsLocalMatch(norm))
        }
    }

    @Test fun diagnoseListsMissingKeys() {
        val d = LocalEngine.diagnoseCloud(null)
        assertTrue(d.contains("Kira key"))
        assertTrue(d.contains("Groq key"))
        assertTrue(d.contains("Gemini key"))
        assertTrue(d.contains("Ollama key"))
        assertTrue(d.contains("Provider keys"))
    }

    @Test fun diagnoseNeverThrows() {
        // Must be bulletproof: it runs on every offline answer.
        repeat(50) { LocalEngine.diagnoseCloud(null) }
    }
}
