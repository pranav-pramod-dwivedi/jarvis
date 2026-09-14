package com.pr4nav.jarvis.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.pr4nav.jarvis.R

/**
 * Renders model markdown (**bold**, *italic*, `code`, ```fences```, headers,
 * lists, quotes, links) into styled chat views instead of raw dumped text.
 */
object Markdown {

    private const val BODY = "#E6EDF6"
    private const val DIM = "#94A3B8"
    private const val ACCENT = "#38BDF8"
    private const val INLINE_CODE_BG = "#1E293B"
    private const val INLINE_CODE_FG = "#7DD3FC"

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** Builds a vertical stack of views for a full message. */
    fun renderMessage(ctx: Context, raw: String, textSizeSp: Float = 13.5f): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // Split out fenced code blocks first so they render as cards.
        val fence = Regex("```(\\w*)\\n?([\\s\\S]*?)(?:```|$)")
        var last = 0
        for (m in fence.findAll(raw)) {
            val before = raw.substring(last, m.range.first).trim()
            if (before.isNotBlank()) root.addView(renderProse(ctx, before, textSizeSp))
            val lang = m.groupValues[1].trim().ifBlank { "code" }
            root.addView(CodeBlockView(ctx, lang, m.groupValues[2].trimEnd()))
            last = m.range.last + 1
        }
        val tail = raw.substring(last).trim()
        if (tail.isNotBlank()) root.addView(renderProse(ctx, tail, textSizeSp))
        return root
    }

    /** Renders non-fence prose: headers, lists, quotes, rules, paragraphs. */
    fun renderProse(ctx: Context, raw: String, textSizeSp: Float = 13.5f): TextView {
        val tv = TextView(ctx).apply {
            setTextColor(Color.parseColor(BODY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setLineSpacing(0f, 1.25f)
            movementMethod = LinkMovementMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val out = SpannableStringBuilder()
        val lines = raw.lines()
        var i = 0
        var para = StringBuilder()
        fun flushPara() {
            if (para.isNotEmpty()) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(inline(para.toString().trim()))
                para = StringBuilder()
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.isEmpty() -> flushPara()
                t.matches(Regex("^#{1,4}\\s+.*")) -> {
                    flushPara()
                    if (out.isNotEmpty()) out.append("\n\n")
                    val level = t.takeWhile { it == '#' }.length
                    val text = t.drop(level).trim()
                    val start = out.length
                    out.append(inline(text))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(if (level == 1) 1.18f else 1.1f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                t.matches(Regex("^([-*•]\\s+|\\d+[.)]\\s+).*")) -> {
                    flushPara()
                    if (out.isNotEmpty()) out.append("\n")
                    val bullet = if (t.matches(Regex("^\\d+[.)]\\s+.*"))) {
                        t.substringBefore(' ').substringBefore('.').substringBefore(')') + ". "
                    } else "•  "
                    val text = t.replaceFirst(Regex("^([-*•]\\s+|\\d+[.)]\\s+)"), "")
                    val start = out.length
                    out.append(bullet)
                    out.append(inline(text))
                    out.setSpan(LeadingMarginSpan.Standard(dp(ctx, 14), 0), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                t.startsWith(">") -> {
                    flushPara()
                    if (out.isNotEmpty()) out.append("\n")
                    val start = out.length
                    out.append(inline(t.removePrefix(">").trim()))
                    out.setSpan(QuoteSpan(Color.parseColor("#334155")), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(Color.parseColor(DIM)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> {
                    flushPara()
                    if (out.isNotEmpty()) out.append("\n")
                    val start = out.length
                    out.append("────────────────")
                    out.setSpan(ForegroundColorSpan(Color.parseColor("#334155")), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(t)
                }
            }
            i++
        }
        flushPara()
        tv.text = out
        return tv
    }

    /** Inline spans: `code`, **bold**, *italic*, ~~strike~~, [text](url). */
    fun inline(raw: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        // Tokenize code spans first so symbols inside code are untouched.
        val codeRe = Regex("`([^`\\n]+)`")
        var last = 0
        for (m in codeRe.findAll(raw)) {
            applyInlineStyles(out, raw.substring(last, m.range.first))
            val start = out.length
            out.append(m.groupValues[1])
            out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(BackgroundColorSpan(Color.parseColor(INLINE_CODE_BG)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(Color.parseColor(INLINE_CODE_FG)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.92f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.last + 1
        }
        applyInlineStyles(out, raw.substring(last))
        return out
    }

    private fun applyInlineStyles(out: SpannableStringBuilder, raw: String) {
        // Links: [text](url)
        val linkRe = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
        var last = 0
        for (m in linkRe.findAll(raw)) {
            applyEmphasis(out, raw.substring(last, m.range.first))
            val start = out.length
            out.append(m.groupValues[1])
            out.setSpan(URLSpan(m.groupValues[2]), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(Color.parseColor(ACCENT)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.last + 1
        }
        applyEmphasis(out, raw.substring(last))
    }

    private fun applyEmphasis(out: SpannableStringBuilder, raw: String) {
        // ~~strike~~
        val strikeRe = Regex("~~(.+?)~~")
        var last = 0
        val tmp = StringBuilder()
        val strikeRanges = mutableListOf<Pair<Int, Int>>()
        for (m in strikeRe.findAll(raw)) {
            tmp.append(raw.substring(last, m.range.first))
            val s = tmp.length
            tmp.append(m.groupValues[1])
            strikeRanges.add(s to tmp.length)
            last = m.range.last + 1
        }
        tmp.append(raw.substring(last))
        val base = out.length
        applyBoldItalic(out, tmp.toString())
        for ((s, e) in strikeRanges) {
            out.setSpan(StrikethroughSpan(), base + s, base + e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun applyBoldItalic(out: SpannableStringBuilder, raw: String) {
        // Single-pass tokenizer: **bold** and *italic* with exact span offsets.
        val token = Regex("\\*\\*(.+?)\\*\\*|(?:^|[^\\w*])\\*([^\\*\\n]+)\\*")
        var last = 0
        for (m in token.findAll(raw)) {
            var head = raw.substring(last, m.range.first)
            var body = ""
            var style = -1
            if (m.groupValues[1].isNotEmpty()) {
                body = m.groupValues[1]
                style = Typeface.BOLD
            } else {
                // Keep a leading non-* prefix char (space/punct) outside the span.
                val full = m.value
                val star = full.indexOf('*')
                head += full.substring(0, star)
                body = m.groupValues[2]
                style = Typeface.ITALIC
            }
            out.append(head)
            val s = out.length
            out.append(body)
            if (body.isNotEmpty()) {
                out.setSpan(StyleSpan(style), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        out.append(raw.substring(last))
    }

    /** Strips markdown + emoji + noise for spoken/short display. */
    fun stripToPlain(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "")
            .replace(Regex("[`*#_~>|]"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
