package com.pr4nav.jarvis.chat

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pr4nav.jarvis.R

/**
 * Claude-style thinking block. While live it shows an animated "Thinking"
 * header with the first line of reasoning; on done it collapses to
 * "Thought for Xs" and expands on tap. Body is sanitized prose only.
 */
class ThinkingBlock(context: Context) : LinearLayout(context) {

    private val headerRow: LinearLayout
    private val statusText: TextView
    private val chev: ImageView
    private val bodyText: TextView
    private val bodyScroll: LinearLayout
    private val buffer = StringBuilder()

    private var live = true
    private var expanded = true
    private var t0 = System.currentTimeMillis()
    private var pulseAnim: android.animation.ObjectAnimator? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_thinking_block)
        val pad = ChatUi.dp(context, 14)
        setPadding(pad, ChatUi.dp(context, 10), pad, ChatUi.dp(context, 10))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = ChatUi.dp(context, 10)
            marginEnd = ChatUi.dp(context, 40)
        }

        headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val brain: ImageView = ChatUi.icon(context, R.drawable.ic_brain, "#A78BFA", 18)
        headerRow.addView(brain)

        statusText = TextView(context).apply {
            text = "Thinking…"
            setTextColor(Color.parseColor("#C4B5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(context, 8)
            }
        }
        headerRow.addView(statusText)

        chev = ChatUi.icon(context, R.drawable.ic_chev, "#64748B", 16)
        headerRow.addView(chev)
        headerRow.setOnClickListener { toggle() }
        addView(headerRow)

        bodyScroll = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = ChatUi.dp(context, 6)
            }
        }
        bodyText = TextView(context).apply {
            setTextColor(Color.parseColor("#9FB0C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setLineSpacing(0f, 1.3f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        bodyScroll.addView(bodyText)
        addView(bodyScroll)

        // Gentle pulse while thinking.
        pulseAnim = android.animation.ObjectAnimator.ofFloat(brain, "alpha", 1f, 0.35f, 1f).apply {
            duration = 1400
            repeatCount = android.animation.ObjectAnimator.INFINITE
            start()
        }
    }

    /** Appends a reasoning fragment (already sanitized or raw — sanitized on finish). */
    fun appendDelta(fragment: String) {
        if (fragment.isBlank()) return
        if (buffer.isNotEmpty()) buffer.append("\n\n")
        buffer.append(fragment.trim())
        post {
            if (!live) return@post
            val preview = ThinkingSanitizer.sanitize(buffer.toString(), 900)
            bodyText.text = preview.ifBlank { "…" }
            val first = preview.lines().firstOrNull { it.isNotBlank() }?.take(64)
            if (!first.isNullOrBlank()) statusText.text = first + "…"
        }
    }

    /** Collapses into "Thought for Xs". */
    fun finish(durationMs: Long) {
        live = false
        post {
            try { pulseAnim?.cancel() } catch (_: Exception) { }
            val clean = ThinkingSanitizer.sanitize(buffer.toString())
            if (clean.isBlank()) {
                visibility = View.GONE
                return@post
            }
            bodyText.text = clean
            statusText.text = "Thought for ${ChatUi.formatDuration(durationMs)}"
            statusText.setTextColor(Color.parseColor("#7C8AA5"))
            expanded = false
            bodyScroll.visibility = View.GONE
            chev.rotation = -90f
        }
    }

    private fun toggle() {
        if (live) return
        expanded = !expanded
        bodyScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        chev.rotation = if (expanded) 0f else -90f
    }

    /** Snapshot for session persistence. */
    fun snapshot(): String = ThinkingSanitizer.sanitize(buffer.toString())
}
