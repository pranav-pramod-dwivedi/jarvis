package com.pr4nav.jarvis.context

import org.junit.Assert.*
import org.junit.Test

/** History must accumulate — an unrecorded turn means a contextless next turn. */
class ConversationHistoryTest {

    private fun uniq() = "t${System.nanoTime()}"

    private fun mine(base: String): List<Pair<String, String>> =
        ConversationalContext.getRecentTurns(400).filter {
            it.second.startsWith(base)
        }

    @Test fun voiceTurnsRecordFullAnswer() {
        // Regression: Live voice turns recorded "" (empty speech) and vanished.
        val base = "q-voice-${uniq()}"
        val full = "$base full answer body with detail"
        ConversationalContext.recordTurn("$base ?", full)
        val hit = mine(base)
        assertEquals(2, hit.size)
        assertEquals("user", hit[0].first)
        assertEquals("$base ?", hit[0].second)
        assertEquals("assistant", hit[1].first)
        assertEquals(full, hit[1].second)
    }

    @Test fun blankResponsesSkipped() {
        val base = "q-blank-${uniq()}"
        ConversationalContext.recordTurn("$base ?", "")
        ConversationalContext.recordTurn("$base ?", "   ")
        ConversationalContext.recordTurn("", "answer")
        assertTrue(mine(base).isEmpty())
    }

    @Test fun orderAndLimit() {
        val base = "ord-${uniq()}"
        repeat(5) { i -> ConversationalContext.recordTurn("$base-q$i", "$base-a$i") }
        val m = mine(base)
        assertEquals(10, m.size) // 5 user + 5 assistant entries, in order
        for (i in 0 until 5) {
            assertEquals("user", m[i * 2].first)
            assertEquals("$base-q$i", m[i * 2].second)
            assertEquals("assistant", m[i * 2 + 1].first)
            assertEquals("$base-a$i", m[i * 2 + 1].second)
        }
        val limited = ConversationalContext.getRecentTurns(2)
        assertTrue(limited.size <= 4)
    }
}
