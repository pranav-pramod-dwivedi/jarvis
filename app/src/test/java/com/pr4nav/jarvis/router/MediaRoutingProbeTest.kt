package com.pr4nav.jarvis.router

import org.junit.Assert.*
import org.junit.Test

/** "Play X" must route to action, never to a refusal. */
class MediaRoutingProbeTest {

    private fun norm(q: String) = try {
        LanguageNormalizer.normalize(q)
    } catch (_: Exception) {
        null
    }

    @Test fun playPhrasingsHitTools() {
        val cases = mapOf(
            "play despacito" to setOf("media.play", "open_app"),
            "play some music" to setOf("media.play", "open_app"),
            "play chill music" to setOf("media.play", "open_app"),
            "open youtube" to setOf("open_app"),
            "open youtube and play despacito" to setOf("open_app", "media.play")
        )
        for ((q, acceptable) in cases) {
            val n = norm(q)
            assertTrue(
                "[$q] normalized to ${n?.tool}@${n?.confidence} — expected one of $acceptable",
                n != null && acceptable.contains(n.tool) && LocalEngine.acceptsLocalMatch(n)
            )
        }
    }
}
