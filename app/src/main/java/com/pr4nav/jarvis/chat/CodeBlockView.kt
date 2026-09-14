package com.pr4nav.jarvis.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pr4nav.jarvis.R

/**
 * Dark card for a fenced code block: language label + copy button on top,
 * monospace body with horizontal scroll.
 */
class CodeBlockView(
    context: Context,
    lang: String,
    code: String
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_code_block)
        val pad = ChatUi.dp(context, 12)
        setPadding(pad, ChatUi.dp(context, 8), pad, pad)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = ChatUi.dp(context, 8)
        }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val langView = TextView(context).apply {
            text = lang.lowercase()
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(langView)

        val copyBtn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(ChatUi.dp(context, 8), ChatUi.dp(context, 4), ChatUi.dp(context, 8), ChatUi.dp(context, 4))
        }
        val copyIcon: ImageView = ChatUi.icon(context, R.drawable.ic_copy, "#94A3B8", 14)
        copyBtn.addView(copyIcon)
        val copyLabel = TextView(context).apply {
            text = "Copy"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = ChatUi.dp(context, 4)
            }
        }
        copyBtn.addView(copyLabel)
        copyBtn.setOnClickListener {
            try {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { }
        }
        header.addView(copyBtn)
        addView(header)

        val scroll = HorizontalScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = ChatUi.dp(context, 6)
            }
        }
        val body = TextView(context).apply {
            text = code
            setTextColor(Color.parseColor("#E6EDF6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(body)
        addView(scroll)
        visibility = View.VISIBLE
    }
}
