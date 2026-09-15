package com.pr4nav.jarvis.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose markdown renderer for agent messages: **bold**, *italic*,
 * `code`, ```fenced blocks```, # headers, - bullets, 1. lists,
 * > quotes, --- rules, [links](url). Unclosed markers stay literal,
 * so half-streamed text still renders sanely.
 */
object ComposeMarkdown {

    /** Pure fence splitter: alternating prose strings and code blocks. */
    fun splitFences(raw: String): List<Any> {
        val out = mutableListOf<Any>()
        val fence = Regex("```(\\w*)\\n?([\\s\\S]*?)(?:```|$)")
        var last = 0
        for (m in fence.findAll(raw)) {
            val before = raw.substring(last, m.range.first).trim()
            if (before.isNotBlank()) out.add(before)
            val lang = m.groupValues[1].trim().ifBlank { "code" }
            out.add(MdCodeBlock(lang, m.groupValues[2].trimEnd()))
            last = m.range.last + 1
        }
        val tail = raw.substring(last).trim()
        if (tail.isNotBlank()) out.add(tail)
        if (out.isEmpty()) out.add(raw)
        return out
    }

    data class MdCodeBlock(val lang: String, val code: String)

    /** Inline spans for one text run: code, links, bold, italic, strike. */
    fun inline(
        raw: String,
        codeBg: Color = Color(0xFF1E293B),
        codeFg: Color = Color(0xFF7DD3FC),
        linkFg: Color = Color(0xFF38BDF8)
    ): AnnotatedString {
        return buildAnnotatedString {
            // Code spans first so symbols inside code are untouched.
            val codeRe = Regex("`([^`\\n]+)`")
            var last = 0
            for (m in codeRe.findAll(raw)) {
                appendInlineStyles(raw.substring(last, m.range.first), linkFg)
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBg,
                        color = codeFg,
                        fontSize = 13.sp
                    )
                ) {
                    append(m.groupValues[1])
                }
                last = m.range.last + 1
            }
            appendInlineStyles(raw.substring(last), linkFg)
        }
    }

    private fun AnnotatedString.Builder.appendInlineStyles(raw: String, linkFg: Color) {
        val linkRe = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
        var last = 0
        for (m in linkRe.findAll(raw)) {
            appendEmphasis(raw.substring(last, m.range.first))
            pushStringAnnotation(tag = "URL", annotation = m.groupValues[2])
            withStyle(
                SpanStyle(color = linkFg, textDecoration = TextDecoration.Underline)
            ) {
                append(m.groupValues[1])
            }
            pop()
            last = m.range.last + 1
        }
        appendEmphasis(raw.substring(last))
    }

    private fun AnnotatedString.Builder.appendEmphasis(raw: String) {
        // Single-pass tokenizer: strike, bold, italic with exact offsets.
        val token = Regex("~~(.+?)~~|\\*\\*(.+?)\\*\\*|(?:^|[^\\w*])\\*([^\\*\\n]+)\\*")
        var last = 0
        for (m in token.findAll(raw)) {
            var head = raw.substring(last, m.range.first)
            val body: String
            val style: SpanStyle
            when {
                m.groupValues[1].isNotEmpty() -> {
                    body = m.groupValues[1]
                    style = SpanStyle(textDecoration = TextDecoration.LineThrough)
                }
                m.groupValues[2].isNotEmpty() -> {
                    body = m.groupValues[2]
                    style = SpanStyle(fontWeight = FontWeight.Bold)
                }
                else -> {
                    val full = m.value
                    val star = full.indexOf('*')
                    head += full.substring(0, star)
                    body = m.groupValues[3]
                    style = SpanStyle(fontStyle = FontStyle.Italic)
                }
            }
            append(head)
            withStyle(style) { append(body) }
            last = m.range.last + 1
        }
        append(raw.substring(last))
    }

    /** Block structure: headers, lists, quotes, rules, paragraphs. */
    fun prose(
        raw: String,
        bodySize: androidx.compose.ui.unit.TextUnit = 14.5.sp,
        bodyColor: Color = Color.White,
        dimColor: Color = Color.White.copy(alpha = 0.55f)
    ): AnnotatedString {
        return buildAnnotatedString {
            val lines = raw.lines()
            var i = 0
            val para = StringBuilder()
            fun flushPara() {
                if (para.isNotEmpty()) {
                    if (length > 0) append("\n\n")
                    appendInlineInto(para.toString().trim(), bodySize, bodyColor)
                    para.clear()
                }
            }
            while (i < lines.size) {
                val t = lines[i].trim()
                when {
                    t.isEmpty() -> flushPara()
                    t.matches(Regex("^#{1,4}\\s+.*")) -> {
                        flushPara()
                        if (length > 0) append("\n\n")
                        val level = t.takeWhile { it == '#' }.length
                        val start = length
                        appendInlineInto(
                            t.drop(level).trim(), bodySize,
                            bodyColor, bold = true
                        )
                        addStyle(
                            SpanStyle(
                                fontSize = if (level == 1) 18.sp else 16.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            start, length
                        )
                        append("\n")
                        addStyle(
                            ParagraphStyle(lineHeight = if (level == 1) 24.sp else 22.sp),
                            start, length
                        )
                    }
                    t.matches(Regex("^([-*•]\\s+|\\d+[.)]\\s+).*")) -> {
                        flushPara()
                        if (length > 0) append("\n")
                        val numbered = t.matches(Regex("^\\d+[.)]\\s+.*"))
                        val bullet = if (numbered) {
                            t.substringBefore(' ').substringBefore('.').substringBefore(')') + ". "
                        } else "•  "
                        val body = t.replaceFirst(Regex("^([-*•]\\s+|\\d+[.)]\\s+)"), "")
                        val start = length
                        append(bullet)
                        appendInlineInto(body, bodySize, bodyColor)
                        append("\n")
                    }
                    t.startsWith(">") -> {
                        flushPara()
                        if (length > 0) append("\n")
                        val start = length
                        append("│ ")
                        appendInlineInto(t.removePrefix(">").trim(), bodySize, dimColor, italic = true)
                        append("\n")
                    }
                    t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> {
                        flushPara()
                        if (length > 0) append("\n")
                        val start = length
                        append("────────")
                        addStyle(SpanStyle(color = Color.White.copy(alpha = 0.25f)), start, length)
                        append("\n")
                    }
                    else -> {
                        if (para.isNotEmpty()) para.append(' ')
                        para.append(t)
                    }
                }
                i++
            }
            flushPara()
        }
    }

    private fun AnnotatedString.Builder.appendInlineInto(
        text: String,
        bodySize: androidx.compose.ui.unit.TextUnit,
        bodyColor: Color,
        bold: Boolean = false,
        italic: Boolean = false
    ) {
        val styled = inline(text)
        val start = length
        append(styled)
        if (bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
        if (italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
    }

    // ── Composables ──────────────────────────────────────────────────────────

    @Composable
    fun Content(
        text: String,
        bodyFontFamily: FontFamily,
        baseSize: androidx.compose.ui.unit.TextUnit = 14.5.sp,
        baseColor: Color = Color.White
    ) {
        val uriHandler = LocalUriHandler.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            splitFences(text).forEach { part ->
                when (part) {
                    is MdCodeBlock -> CodeCard(lang = part.lang, code = part.code)
                    is String -> {
                        val annotated = prose(part, baseSize, baseColor)
                        ClickableText(
                            text = annotated,
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = bodyFontFamily,
                                fontSize = baseSize,
                                lineHeight = 21.sp,
                                color = baseColor
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { ann ->
                                        try {
                                            uriHandler.openUri(ann.item)
                                        } catch (_: Exception) { }
                                    }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun CodeCard(lang: String, code: String) {
        val clipboard = LocalClipboardManager.current
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF0D1117),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Text(
                        text = lang.lowercase(),
                        color = Color(0xFF7DD3FC),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    androidx.compose.material3.TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(code))
                        }
                    ) {
                        androidx.compose.material3.Text(
                            text = "Copy",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    androidx.compose.material3.Text(
                        text = code,
                        color = Color(0xFFE6EDF6),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
