package com.pr4nav.jarvis.chat

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pr4nav.jarvis.R

/**
 * Agent + user message bubbles with SVG icon action rows.
 * No emojis anywhere on this surface.
 */
object AgentBubbles {

    data class Actions(
        val fullText: String,
        val prompt: String,
        val onListen: (String) -> Unit,
        val onRegenerate: (String) -> Unit,
        val onStopSpeak: () -> Unit
    )

    fun userBubble(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.bg_chat_user)
            val d = ChatUi.dp(ctx, 1)
            setPadding(36 * d / 3, 24 * d / 3, 36 * d / 3, 24 * d / 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = ChatUi.dp(ctx, 10)
                marginStart = ChatUi.dp(ctx, 60)
            }
        }
    }

    /** Full agent card: markdown body + optional caption + icon action row. */
    fun agentCard(
        ctx: Context,
        markdown: String,
        caption: String? = null,
        actions: Actions? = null
    ): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_chat_agent)
            val d = ChatUi.dp(ctx, 1)
            setPadding(36 * d / 3, 32 * d / 3, 36 * d / 3, 32 * d / 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                topMargin = ChatUi.dp(ctx, 10)
                marginEnd = ChatUi.dp(ctx, 20)
            }
        }
        card.addView(Markdown.renderMessage(ctx, markdown))
        if (!caption.isNullOrBlank()) {
            val cap = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = ChatUi.dp(ctx, 8) }
            }
            cap.addView(ChatUi.icon(ctx, R.drawable.ic_bolt, "#475569", 12))
            val tv = TextView(ctx).apply {
                text = caption
                setTextColor(Color.parseColor("#475569"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = ChatUi.dp(ctx, 4) }
            }
            cap.addView(tv)
            card.addView(cap)
        }
        if (actions != null) card.addView(actionRow(ctx, actions))
        return card
    }

    /** Compact static thinking for session history. */
    fun staticThinking(ctx: Context, text: String): LinearLayout {
        val clean = ThinkingSanitizer.sanitize(text, 1500)
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_thinking_block)
            val p = ChatUi.dp(ctx, 12)
            setPadding(p, ChatUi.dp(ctx, 8), p, ChatUi.dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = ChatUi.dp(ctx, 8)
                marginEnd = ChatUi.dp(ctx, 40)
            }
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ChatUi.icon(ctx, R.drawable.ic_brain, "#7C8AA5", 15))
        val tv = TextView(ctx).apply {
            this.text = "Thought"
            setTextColor(Color.parseColor("#7C8AA5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(ctx, 6)
            }
        }
        head.addView(tv)
        wrap.addView(head)
        val body = TextView(ctx).apply {
            this.text = clean
            setTextColor(Color.parseColor("#9FB0C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(ctx, 4) }
        }
        wrap.addView(body)
        return wrap
    }

    /** Slim finished-tool row for session history. */
    fun toolHistoryRow(ctx: Context, summary: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tool_card)
            val p = ChatUi.dp(ctx, 10)
            setPadding(p, ChatUi.dp(ctx, 7), p, ChatUi.dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = ChatUi.dp(ctx, 6)
                marginEnd = ChatUi.dp(ctx, 40)
            }
        }
        row.addView(ChatUi.icon(ctx, R.drawable.ic_check, "#10B981", 15))
        val tv = TextView(ctx).apply {
            text = summary
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(ctx, 8)
            }
        }
        row.addView(tv)
        return row
    }

    /** Three-dot typing indicator. */
    fun typingBubble(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_chat_agent)
            setPadding(ChatUi.dp(ctx, 16), ChatUi.dp(ctx, 12), ChatUi.dp(ctx, 16), ChatUi.dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(ctx, 8) }
        }
        repeat(3) { idx ->
            val dot = View(ctx).apply {
                val s = ChatUi.dp(ctx, 7)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginStart = if (idx == 0) 0 else ChatUi.dp(ctx, 5)
                }
                setBackgroundResource(R.drawable.bg_dot)
                alpha = 0.35f
            }
            row.addView(dot)
            val anim = ObjectAnimator.ofFloat(dot, "alpha", 0.35f, 1f, 0.35f).apply {
                duration = 900
                startDelay = idx * 180L
                repeatCount = ObjectAnimator.INFINITE
            }
            anim.start()
            dot.tag = anim
        }
        return row
    }

    fun stopTypingAnims(row: LinearLayout) {
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).tag as? ObjectAnimator)?.cancel()
        }
    }

    private fun actionRow(ctx: Context, a: Actions): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(ctx, 8) }
        }
        row.addView(actionPill(ctx, R.drawable.ic_copy, "Copy", "#94A3B8") {
            try {
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS Response", a.fullText))
                Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { }
        })
        row.addView(actionPill(ctx, R.drawable.ic_refresh, "Regen", "#94A3B8") { a.onRegenerate(a.prompt) })
        row.addView(actionPill(ctx, R.drawable.ic_speaker, "Listen", "#94A3B8") { a.onListen(a.fullText) })
        row.addView(actionPill(ctx, R.drawable.ic_stop, "Stop", "#EF4444") { a.onStopSpeak() })
        return row
    }

    private fun actionPill(
        ctx: Context,
        iconRes: Int,
        text: String,
        colorHex: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_btn_action_pill)
            val p = ChatUi.dp(ctx, 8)
            setPadding(p, ChatUi.dp(ctx, 5), p, ChatUi.dp(ctx, 5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = ChatUi.dp(ctx, 8) }
            isClickable = true
            isFocusable = true
            addView(ChatUi.icon(ctx, iconRes, colorHex, 13))
            val tv = TextView(ctx).apply {
                this.text = text
                setTextColor(Color.parseColor(colorHex))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = ChatUi.dp(ctx, 4) }
            }
            addView(tv)
            setOnClickListener { onClick() }
        }
    }
}
