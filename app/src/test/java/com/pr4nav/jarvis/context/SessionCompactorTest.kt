package com.pr4nav.jarvis.context

import com.pr4nav.jarvis.session.SessionMessage
import org.junit.Assert.*
import org.junit.Test

/** Brutal tests for /compact: never lose the tail, always keep a head. */
class SessionCompactorTest {

    private fun msg(sender: String, text: String, toolCall: String? = null): SessionMessage =
        SessionMessage(sender = sender, text = text, toolCall = toolCall)

    private fun bigSession(n: Int = 100): MutableList<SessionMessage> {
        val list = mutableListOf<SessionMessage>()
        for (i in 1..n) {
            if (i % 2 == 1) {
                list.add(msg("user", "user request number $i about topic${i % 7}"))
            } else {
                list.add(
                    msg(
                        "agent", "agent answer $i with some detail",
                        toolCall = if (i % 4 == 0) "execute_shell_command" else null
                    )
                )
            }
        }
        return list
    }

    @Test fun smallSessionsUntouched() {
        assertTrue(SessionCompactor.compact(emptyList()).isEmpty())
        val small = bigSession(10)
        assertEquals(10, SessionCompactor.compact(small).size)
        assertEquals(small, SessionCompactor.compact(small))
        val edge = bigSession(SessionCompactor.KEEP_TAIL + 1)
        assertEquals(edge.size, SessionCompactor.compact(edge).size)
    }

    @Test fun compactKeepsTailOrder() {
        val msgs = bigSession(100)
        val out = SessionCompactor.compact(msgs, keepTail = 20)
        assertEquals(21, out.size)
        assertEquals("system", out[0].sender)
        assertEquals(msgs.takeLast(20), out.drop(1))
    }

    @Test fun summaryCapturesTopicsAndTools() {
        val msgs = bigSession(100)
        val summary = SessionCompactor.summarize(msgs)
        assertTrue(summary.contains("user request number"))
        assertTrue(summary.contains("execute_shell_command"))
        assertTrue(summary.contains("100 older messages"))
        assertTrue(summary.contains("Turns:"))
    }

    @Test fun needsCompactThreshold() {
        assertFalse(SessionCompactor.needsCompact(bigSession(79)))
        assertTrue(SessionCompactor.needsCompact(bigSession(80)))
        assertTrue(SessionCompactor.needsCompact(bigSession(500)))
    }

    @Test fun doubleCompactIsStable() {
        val once = SessionCompactor.compact(bigSession(200), keepTail = 20)
        assertEquals(21, once.size)
        // Compacting an already-compact session is a no-op.
        assertEquals(21, SessionCompactor.compact(once, keepTail = 20).size)
    }

    @Test fun hostileInputs() {
        val weird = listOf(
            msg("user", ""),
            msg("user", "   "),
            msg("agent", "x".repeat(100_000)),
            msg("system", "null"),
            msg("", ""),
            msg("user", "😂🔥".repeat(500))
        )
        val out = SessionCompactor.compact(weird + bigSession(100), keepTail = 5)
        assertEquals(6, out.size)
        assertEquals("system", out[0].sender)
        assertTrue(out[0].text.isNotBlank())
    }

    @Test fun emptySummary() {
        assertEquals("Empty context.", SessionCompactor.summarize(emptyList()))
    }
}
