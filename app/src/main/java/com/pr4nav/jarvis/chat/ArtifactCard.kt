package com.pr4nav.jarvis.chat

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.pr4nav.jarvis.R

/**
 * Inline card for a saved artifact / mini-app: icon + title + type chip
 * and an Open button that launches it in a fresh tab.
 */
class ArtifactCard(
    context: Context,
    title: String,
    type: String,
    onOpen: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_artifact_card)
        val pad = ChatUi.dp(context, 12)
        setPadding(pad, ChatUi.dp(context, 10), pad, ChatUi.dp(context, 10))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = ChatUi.dp(context, 8)
            marginEnd = ChatUi.dp(context, 40)
        }

        addView(ChatUi.icon(context, R.drawable.ic_layers, "#10B981", 22))

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(context, 10)
            }
        }
        val titleView = TextView(context).apply {
            text = title.ifBlank { "Artifact" }
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        texts.addView(titleView)
        val typeView = TextView(context).apply {
            text = "Saved to Artifacts · ${type.lowercase().replace('_', ' ')}"
            setTextColor(Color.parseColor("#6EE7B7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        texts.addView(typeView)
        addView(texts)

        val open = TextView(context).apply {
            text = "Open"
            setTextColor(Color.parseColor("#06281C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            try {
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_active_pill)
            } catch (_: Exception) {
                setBackgroundColor(Color.parseColor("#10B981"))
            }
            val bp = ChatUi.dp(context, 8)
            setPadding(bp * 2, bp, bp * 2, bp)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = ChatUi.dp(context, 8)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpen() }
        }
        addView(open)
        visibility = View.VISIBLE
    }
}
