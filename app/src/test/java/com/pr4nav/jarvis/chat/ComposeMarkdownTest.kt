package com.pr4nav.jarvis.chat

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.*
import org.junit.Test

/** Brutal tests for the Compose markdown parser (agent symbols). */
class ComposeMarkdownTest {

    @Test fun fencesSplitCleanly() {
        val parts = ComposeMarkdown.splitFences("intro\n\n```python\nx = 1\n```\n\nmid\n\n```\ny\n```\n\nout")
        assertEquals(5, parts.size)
        assertTrue(parts[0] is String)
        val c1 = parts[1] as ComposeMarkdown.MdCodeBlock
        assertEquals("python", c1.lang)
        assertEquals("x = 1", c1.code)
        val c2 = parts[3] as ComposeMarkdown.MdCodeBlock
        assertEquals("code", c2.lang)
        assertEquals("out", (parts[4] as String))
    }

    @Test fun unclosedFenceSwallowsRest() {
        val parts = ComposeMarkdown.splitFences("text\n\n```js\nvar a = 1;")
        assertEquals(2, parts.size)
        val c = parts[1] as ComposeMarkdown.MdCodeBlock
        assertEquals("js", c.lang)
        assertTrue(c.code.contains("var a"))
    }

    @Test fun noFenceIsProse() {
        val parts = ComposeMarkdown.splitFences("just **bold** text")
        assertEquals(1, parts.size)
        assertTrue(parts[0] is String)
    }

    @Test fun emptyGivesRaw() {
        assertEquals(listOf(""), ComposeMarkdown.splitFences(""))
    }

    @Test fun boldSpanExact() {
        val a = ComposeMarkdown.inline("say **hello world** now")
        assertEquals("say hello world now", a.text)
        val spans = a.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, spans.size)
        assertEquals("hello world", a.text.substring(spans[0].start, spans[0].end))
    }

    @Test fun italicSpanExact() {
        val a = ComposeMarkdown.inline("a *quiet* word")
        val spans = a.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, spans.size)
        assertEquals("quiet", a.text.substring(spans[0].start, spans[0].end))
    }

    @Test fun codeSpanUntouchedInside() {
        // Symbols inside code must NOT parse: "**" stays literal.
        val a = ComposeMarkdown.inline("run `a ** b` ok")
        assertEquals("run a ** b ok", a.text)
        assertTrue(a.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
        assertTrue(a.spanStyles.any { it.item.fontFamily?.toString()?.contains("Monospace") == true })
    }

    @Test fun linkAnnotation() {
        val a = ComposeMarkdown.inline("see [docs](https://x.y/z) now")
        assertEquals("see docs now", a.text)
        val urls = a.getStringAnnotations("URL", 0, a.length)
        assertEquals(1, urls.size)
        assertEquals("https://x.y/z", urls[0].item)
        assertEquals("docs", a.text.substring(urls[0].start, urls[0].end))
    }

    @Test fun strikeSpan() {
        val a = ComposeMarkdown.inline("this is ~~gone~~ here")
        assertEquals("this is gone here", a.text)
        assertTrue(a.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test fun unclosedMarkersStayLiteral() {
        assertEquals("a ** broken", ComposeMarkdown.inline("a ** broken").text)
        assertEquals("a * broken", ComposeMarkdown.inline("a * broken").text)
        assertEquals("a ` broken", ComposeMarkdown.inline("a ` broken").text)
    }

    @Test fun proseHeadersBulletsQuotes() {
        val a = ComposeMarkdown.prose("## Title\n\n- one\n- two\n\n1. first\n\n> quoted\n\n---\n\ntail")
        val t = a.text
        assertTrue(t.contains("Title"))
        assertFalse(t.contains("##"))
        assertTrue(t.contains("•  one"))
        assertTrue(t.contains("1. first"))
        assertTrue(t.contains("quoted"))
        assertFalse(t.contains("> quoted"))
        assertTrue(t.contains("tail"))
        // Bold applied to header range.
        assertTrue(a.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun proseKeepsCodeMarkersForFenceStage() {
        // prose() never sees fences (splitFences runs first), but inline code works.
        val a = ComposeMarkdown.prose("use `ls -la` now")
        assertEquals("use ls -la now", a.text)
    }

    @Test fun hostileInputs() {
        val nasty = listOf("", "   ", "**", "***", "``", "```", "[]()", "[x]()", "~~", "#", "-", ">", "1.", "*".repeat(5000))
        nasty.forEach { s ->
            assertNotNull(ComposeMarkdown.splitFences(s))
            assertNotNull(ComposeMarkdown.inline(s))
            assertNotNull(ComposeMarkdown.prose(s))
        }
    }
}
