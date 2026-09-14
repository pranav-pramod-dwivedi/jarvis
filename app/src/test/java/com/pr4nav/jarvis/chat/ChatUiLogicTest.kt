package com.pr4nav.jarvis.chat

import org.junit.Assert.*
import org.junit.Test

/** Brutal tests for follow-up chips, chunker and thinking sanitizer. */
class ChatUiLogicTest {

    // ── Followups ─────────────────────────────────────────────────────────────

    @Test fun agentQuestionGetsQuickAnswers() {
        val chips = Followups.forTurn("book a cab", "Which destination should I use?", false, true)
        assertEquals(3, chips.size)
        assertEquals("Yes", chips[0].label)
        assertEquals("Yes", chips[0].send)
        assertEquals("No", chips[1].label)
    }

    @Test fun failedTurnSuggestsRetry() {
        val chips = Followups.forTurn("do the thing", "Route exhausted", false, false)
        assertTrue(chips.any { it.label.contains("Try again") })
        assertTrue(chips[0].send.contains("do the thing"))
    }

    @Test fun codeReplySuggestsCodeActions() {
        val chips = Followups.forTurn("fibonacci", "Here:\n```python\ndef f(): pass\n```", false, true)
        assertEquals(listOf("Explain code", "Simplify", "Example"), chips.map { it.label })
    }

    @Test fun toolTurnSuggestsAudit() {
        val chips = Followups.forTurn("read notes", "Done.", true, true)
        assertTrue(chips.any { it.label.contains("changed") })
        assertTrue(chips.any { it.label.contains("Undo") })
    }

    @Test fun plainChatGetsGeneric() {
        val chips = Followups.forTurn("hi", "Hello! How can I help?", false, true)
        assertEquals(3, chips.size)
    }

    @Test fun emptyReplyGivesNothing() {
        assertTrue(Followups.forTurn("hi", "   ", false, true).isEmpty())
        assertTrue(Followups.forTurn("hi", "", true, false).isEmpty())
    }

    // ── MessageChunker ────────────────────────────────────────────────────────

    @Test fun shortTextStaysSingle() {
        assertEquals(listOf("hello there"), MessageChunker.chunk("hello there"))
        assertTrue(MessageChunker.chunk("   ").isEmpty())
    }

    @Test fun paragraphsSplitAndMerge() {
        val text = "Para one is here.\n\nPara two is here and a bit longer than one."
        val chunks = MessageChunker.chunk(text, maxChars = 60)
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].contains("Para one"))
        assertTrue(chunks[1].contains("Para two"))
    }

    @Test fun codeFencesStayAtomic() {
        val code = "```python\n" + "x = 1\n".repeat(50) + "```"
        val chunks = MessageChunker.chunk("intro\n\n$code\n\noutro", maxChars = 100)
        assertEquals(3, chunks.size)
        assertTrue(chunks[1].startsWith("```python"))
        assertTrue(chunks[1].endsWith("```"))
    }

    @Test fun longParagraphSplitsOnSentences() {
        val para = (1..20).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = MessageChunker.chunk(para, maxChars = 120)
        assertTrue(chunks.size >= 3)
        // No chunk should cut mid-sentence.
        chunks.forEach { assertTrue("chunk=[$it]", it.endsWith(".") || it.length <= 120) }
    }

    @Test fun noEmptyChunks() {
        val chunks = MessageChunker.chunk("\n\n\nA\n\n\n\nB\n\n", maxChars = 10)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue(it.isNotBlank()) }
    }

    // ── ThinkingSanitizer ─────────────────────────────────────────────────────

    @Test fun stripsCodeAndJson() {
        val raw = "I should check the file first.\n```python\nx=1\n```\n{\"action\": \"run\"}\nThen answer."
        val clean = ThinkingSanitizer.sanitize(raw)
        assertFalse(clean.contains("```"))
        assertFalse(clean.contains("x=1"))
        assertFalse(clean.contains("\"action\""))
        assertTrue(clean.contains("check the file"))
        assertTrue(clean.contains("Then answer"))
    }

    @Test fun stripsEmojiAndMarkdown() {
        val clean = ThinkingSanitizer.sanitize("🧠 **Think** `code` #tag > quote **bold** done")
        assertFalse(clean.contains("🧠"))
        assertFalse(clean.contains("**"))
        assertFalse(clean.contains("`"))
        assertTrue(clean.contains("Think"))
    }

    @Test fun blankInBlankOut() {
        assertEquals("", ThinkingSanitizer.sanitize(""))
        assertEquals("", ThinkingSanitizer.sanitize("```code```"))
        assertEquals("", ThinkingSanitizer.sanitize("null"))
    }

    @Test fun capsLength() {
        val long = "word ".repeat(3000)
        val clean = ThinkingSanitizer.sanitize(long, maxChars = 500)
        assertTrue(clean.length <= 510)
        assertTrue(clean.endsWith("…"))
    }

    @Test fun cleanLineStripsNoise() {
        assertEquals("Executing", ThinkingSanitizer.cleanLine("⚡ Executing…"))
        assertEquals("Executing foo", ThinkingSanitizer.cleanLine("⚡ **Executing** `foo`…"))
        assertEquals("", ThinkingSanitizer.cleanLine("null"))
        val long = "x".repeat(300)
        assertTrue(ThinkingSanitizer.cleanLine(long).length <= 141)
    }

    @Test fun cleanLineKillsSubstringTraps() {
        // Symbols gone but real words survive.
        assertEquals("Querying Kira AI (mini-1.0)", ThinkingSanitizer.cleanLine("Querying Kira AI (mini-1.0)…"))
        assertEquals("Run · ls -la · done", ThinkingSanitizer.cleanLine("⚡ **Run** · `ls -la` · done"))
    }
}
