package com.pr4nav.jarvis.chat

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pr4nav.jarvis.R

/**
 * One compact card per tool call: SVG icon + verb ("Read", "Run") + target,
 * status (spinner → check + duration / cross + exit code), output collapsed
 * by default and expandable on tap. Replaces raw dumped tool text.
 */
class ToolCallCard(
    context: Context,
    tool: String,
    detail: String
) : LinearLayout(context) {

    private val meta = ToolMeta.of(tool, detail)
    private var resolvedLabel = meta.label
    private val titleView: TextView
    private val detailView: TextView
    private val statusView: TextView
    private val statusIcon: ImageView
    private val spinner: ProgressBar
    private val outputBox: LinearLayout
    private val outputText: TextView
    private val chev: ImageView
    private var outputExpanded = false
    private var finished = false
    private var fullOutput: String = ""

    var onOpenAction: (() -> Unit)? = null
    private var openBtn: TextView? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_tool_card)
        val pad = ChatUi.dp(context, 12)
        setPadding(pad, ChatUi.dp(context, 10), pad, ChatUi.dp(context, 10))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = ChatUi.dp(context, 8)
            marginEnd = ChatUi.dp(context, 40)
        }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val icon: ImageView = ChatUi.icon(context, meta.iconRes, meta.accent, 20)
        row.addView(icon)

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(context, 10)
            }
        }
        titleView = TextView(context).apply {
            text = meta.label
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        texts.addView(titleView)
        detailView = TextView(context).apply {
            text = detail.ifBlank { tool }
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        texts.addView(detailView)
        row.addView(texts)

        spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LayoutParams(ChatUi.dp(context, 18), ChatUi.dp(context, 18)).apply {
                marginEnd = ChatUi.dp(context, 6)
            }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF7A00"))
        }
        row.addView(spinner)

        statusIcon = ChatUi.icon(context, R.drawable.ic_check, "#10B981", 16)
        statusIcon.visibility = View.GONE
        row.addView(statusIcon)

        statusView = TextView(context).apply {
            text = "running"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = ChatUi.dp(context, 4)
            }
        }
        row.addView(statusView)

        chev = ChatUi.icon(context, R.drawable.ic_chev, "#64748B", 14)
        chev.visibility = View.GONE
        row.addView(chev)

        row.setOnClickListener { if (finished) toggleOutput() }
        addView(row)

        outputBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.bg_code_block)
            val ip = ChatUi.dp(context, 10)
            setPadding(ip, ChatUi.dp(context, 8), ip, ChatUi.dp(context, 8))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = ChatUi.dp(context, 8)
            }
            visibility = View.GONE
        }
        outputText = TextView(context).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        outputBox.addView(outputText)
        addView(outputBox)
    }

    /** Allows late label refinement (e.g. inner device-tool name). */
    fun refineLabel(label: String) {
        if (label.isNotBlank()) {
            resolvedLabel = label
            post { titleView.text = label }
        }
    }

    fun finish(output: String, exitCode: Int, durationMs: Long, actionLabel: String? = null) {
        finished = true
        fullOutput = output.trim()
        val ok = exitCode == 0
        post {
            spinner.visibility = View.GONE
            statusIcon.visibility = View.VISIBLE
            try {
                var d = androidx.core.content.ContextCompat.getDrawable(context,
                    if (ok) R.drawable.ic_check else R.drawable.ic_x)
                if (d != null) {
                    d = androidx.core.graphics.drawable.DrawableCompat.wrap(d.mutate())
                    androidx.core.graphics.drawable.DrawableCompat.setTint(
                        d, Color.parseColor(if (ok) "#10B981" else "#EF4444"))
                    statusIcon.setImageDrawable(d)
                }
            } catch (_: Exception) { }
            statusView.text = if (ok) ChatUi.formatDuration(durationMs)
            else "exit $exitCode · ${ChatUi.formatDuration(durationMs)}"
            statusView.setTextColor(Color.parseColor(if (ok) "#10B981" else "#EF4444"))
            if (fullOutput.isNotBlank()) {
                outputText.text = if (fullOutput.length > 1500) fullOutput.take(1500) + "\n… (truncated)" else fullOutput
                chev.visibility = View.VISIBLE
                chev.rotation = -90f
            }
            if (actionLabel != null && onOpenAction != null) {
                val btn = TextView(context).apply {
                    text = actionLabel
                    setTextColor(Color.parseColor("#10B981"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setBackgroundResource(R.drawable.bg_btn_action_pill)
                    val bp = ChatUi.dp(context, 10)
                    setPadding(bp * 2, bp / 2, bp * 2, bp / 2)
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = ChatUi.dp(context, 8)
                    }
                    setOnClickListener { onOpenAction?.invoke() }
                }
                openBtn = btn
                addView(btn)
            }
        }
    }

    private fun toggleOutput() {
        outputExpanded = !outputExpanded
        outputBox.visibility = if (outputExpanded) View.VISIBLE else View.GONE
        chev.rotation = if (outputExpanded) 0f else -90f
    }

    fun snapshot(): String {
        val state = if (finished) "done" else "running"
        return "$resolvedLabel · $state"
    }
}
