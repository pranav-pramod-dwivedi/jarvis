package com.pr4nav.jarvis.chat

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns one assistant turn in the chat: status pill, thinking block, tool cards,
 * a live streaming bubble, then the chunked multi-bubble finish with typing
 * indicators. All event handling is posted to the UI thread; safe to call
 * from any background thread.
 */
class AgentTurnRenderer(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val scroller: ScrollView,
    private val prompt: String,
    private val listener: Listener
) {

    interface Listener {
        fun onTurnFinished(
            finalText: String,
            thinking: String,
            toolSummaries: List<String>,
            toolNames: String,
            success: Boolean,
            model: String,
            latencyMs: Long
        )
        fun onOpenArtifactFile(filePath: String, title: String, type: String)
        fun onOpenBrowserApp(appId: String)
        fun bubbleActions(fullText: String): AgentBubbles.Actions
    }

    private val t0 = System.currentTimeMillis()
    @Volatile var cancelled = false
        private set

    private var statusRow: LinearLayout? = null
    private var statusText: TextView? = null
    private var statusIcon: ImageView? = null
    private var thinking: ThinkingBlock? = null
    private var thinkingStarted = false
    private var liveCard: LinearLayout? = null
    private var liveBody: LinearLayout? = null
    private val liveText = StreamingReply()
    private var liveRenderAt = 0L
    private val openCards = mutableListOf<ToolCallCard>()
    private val toolSummaries = mutableListOf<String>()
    private val toolNames = mutableListOf<String>()
    private var finished = false

    fun cancel() {
        cancelled = true
    }

    fun isFinished(): Boolean = finished

    fun onEvent(ev: AgentStreamEvent) {
        if (cancelled || finished) return
        activity.runOnUiThread {
            if (cancelled || finished) return@runOnUiThread
            try {
                handle(ev)
            } catch (_: Exception) { }
            scroll()
        }
    }

    // ── event routing ─────────────────────────────────────────────────────────

    private fun handle(ev: AgentStreamEvent) {
        when (ev) {
            is AgentStreamEvent.Status -> showStatus(ev.text)
            is AgentStreamEvent.ThinkingDelta -> {
                ensureThinking().appendDelta(ev.text)
                thinkingStarted = true
            }
            is AgentStreamEvent.ThinkingDone -> {
                thinking?.finish(if (ev.durationMs > 0) ev.durationMs else System.currentTimeMillis() - t0)
                settleStatus("Answering…")
            }
            is AgentStreamEvent.ToolStart -> {
                settleStatus(ev.tool)
                val meta = ToolMeta.of(ev.tool, ev.detail)
                val card = ToolCallCard(activity, ev.tool, ev.detail.ifBlank { ev.tool })
                if (ev.tool.trim().lowercase() == "execute_device_tool") {
                    val inner = ToolMeta.summarizeArgs("execute_device_tool", mapOf("tool_name" to ev.detail))
                    if (inner.isNotBlank() && inner != ev.detail) card.refineLabel(inner)
                }
                container.addView(card)
                openCards.add(card)
                voidUnused(meta)
            }
            is AgentStreamEvent.ToolEnd -> {
                val card = openCards.lastOrNull()
                val meta = ToolMeta.of(ev.tool, ev.detail)
                val label = if (ev.label.isNotBlank()) ev.label else meta.label
                card?.refineLabel(label)
                val detail = ev.detail.ifBlank { ev.tool }
                val ok = ev.exitCode == 0 && ev.verified
                val summary = "$label · $detail · ${if (ok) "done" else "exit ${ev.exitCode}"} · ${ChatUi.formatDuration(ev.durationMs)}"
                toolSummaries.add(summary.take(220))
                if (!toolNames.contains(ev.tool)) toolNames.add(ev.tool)
                if (ev.tool.trim().lowercase() == "browser_render_app" && ev.actionId != null) {
                    card?.onOpenAction = { listener.onOpenBrowserApp(ev.actionId) }
                    card?.finish(ev.output, ev.exitCode, ev.durationMs, "Open app")
                } else {
                    card?.finish(ev.output, ev.exitCode, ev.durationMs)
                }
                settleStatus("Working…")
            }
            is AgentStreamEvent.TextDelta -> appendLive(ev.text)
            is AgentStreamEvent.ArtifactSaved -> {
                container.addView(
                    ArtifactCard(activity, ev.title, ev.type) {
                        listener.onOpenArtifactFile(ev.filePath, ev.title, ev.type)
                    }
                )
            }
            is AgentStreamEvent.Final -> finishTurn(ev)
            is AgentStreamEvent.Error -> {
                ensureLiveCard()
                appendLive("\n\nSomething went wrong: ${ev.message}")
                finalizeBubbles(ev.message, success = false, model = "", tools = 0, thinking = thinking?.snapshot().orEmpty())
            }
        }
    }

    // ── pieces ────────────────────────────────────────────────────────────────

    private fun showStatus(text: String) {
        val clean = text.trim().take(90)
        if (clean.isBlank()) return
        if (statusRow == null) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = ChatUi.dp(activity, 10) }
            }
            statusIcon = ChatUi.icon(activity, R.drawable.ic_bolt, "#FF7A00", 13)
            row.addView(statusIcon)
            statusText = TextView(activity).apply {
                setTextColor(Color.parseColor("#FF9E44"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = ChatUi.dp(activity, 6) }
            }
            row.addView(statusText)
            container.addView(row)
            statusRow = row
        }
        statusText?.text = clean
    }

    private fun settleStatus(text: String) {
        if (statusRow != null) statusText?.text = text
    }

    private fun ensureThinking(): ThinkingBlock {
        var b = thinking
        if (b == null) {
            b = ThinkingBlock(activity)
            thinking = b
            // Anchor directly under the status row so it never drifts below tool cards.
            val anchor = statusRow
            if (anchor != null) {
                val idx = container.indexOfChild(anchor)
                if (idx >= 0) container.addView(b, idx + 1) else container.addView(b)
            } else {
                container.addView(b)
            }
        }
        return b
    }

    private fun ensureLiveCard() {
        if (liveCard != null) return
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_chat_agent)
            val d = ChatUi.dp(activity, 1)
            setPadding(36 * d / 3, 32 * d / 3, 36 * d / 3, 32 * d / 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                topMargin = ChatUi.dp(activity, 10)
                marginEnd = ChatUi.dp(activity, 20)
            }
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(Markdown.renderProse(activity, "…"))
        card.addView(body)
        container.addView(card)
        liveCard = card
        liveBody = body
    }

    private fun appendLive(text: String) {
        if (text.isEmpty()) return
        ensureLiveCard()
        liveText.append(text)
        val snapshot = liveText.snapshot()
        val now = System.currentTimeMillis()
        if (now - liveRenderAt < 450 && snapshot.length > 400) return
        liveRenderAt = now
        val body = liveBody ?: return
        body.removeAllViews()
        val capped = if (snapshot.length > 6000) snapshot.take(6000) + "\n\n…" else snapshot
        body.addView(Markdown.renderMessage(activity, capped))
    }

    private fun finishTurn(ev: AgentStreamEvent.Final) {
        val combined = liveText.finish(ev.text).ifBlank { "Done." }
        finalizeBubbles(
            combined,
            success = ev.handled,
            model = ev.model,
            tools = if (ev.toolsUsed > 0) ev.toolsUsed else toolNames.size,
            thinking = thinking?.snapshot().orEmpty().ifBlank { ev.thinkingTrace }
        )
    }

    private fun finalizeBubbles(
        fullText: String,
        success: Boolean,
        model: String,
        tools: Int,
        thinking: String
    ) {
        finished = true
        // Drop the live card; the chunked bubbles replace it.
        liveCard?.let { container.removeView(it) }
        liveCard = null
        statusRow?.let { container.removeView(it) }
        statusRow = null

        autoSaveHtmlArtifacts(fullText)

        val caption = buildString {
            if (model.isNotBlank()) append(model)
            val ms = System.currentTimeMillis() - t0
            if (isNotEmpty()) append(" · ")
            append(ChatUi.formatDuration(ms))
            if (tools > 0) append(" · $tools tool${if (tools == 1) "" else "s"}")
        }
        val chunks = MessageChunker.chunk(fullText).ifEmpty { listOf(fullText) }
        val cleanThinking = ThinkingSanitizer.sanitize(thinking)

        // Persist + notify immediately; bubbles stagger in visually.
        listener.onTurnFinished(fullText, cleanThinking, toolSummaries.toList(),
            toolNames.joinToString(","), success, model, System.currentTimeMillis() - t0)

        var delay = 0L
        chunks.forEachIndexed { idx, chunk ->
            val isLast = idx == chunks.size - 1
            val cap = if (isLast) caption else null
            if (idx > 0) {
                delay += 90
                activity.window?.decorView?.postDelayed({
                    if (cancelled) return@postDelayed
                    val typing = AgentBubbles.typingBubble(activity)
                    container.addView(typing)
                    scroll()
                    typing.postDelayed({
                        container.removeView(typing)
                        AgentBubbles.stopTypingAnims(typing)
                        if (cancelled) return@postDelayed
                        container.addView(AgentBubbles.agentCard(activity, chunk, cap,
                            listener.bubbleActions(fullText)))
                        scroll()
                    }, 160)
                }, delay)
                delay += 140 + (chunk.length.coerceAtMost(800) / 8)
            } else {
                activity.window?.decorView?.postDelayed({
                    if (cancelled) return@postDelayed
                    container.addView(AgentBubbles.agentCard(activity, chunk, cap,
                        listener.bubbleActions(fullText)))
                    scroll()
                }, delay)
            }
        }
    }

    /** Long inline HTML fences become saved artifacts with an openable card. */
    private fun autoSaveHtmlArtifacts(fullText: String) {
        try {
            val fence = Regex("```html\\n?([\\s\\S]*?)```")
            val blocks = fence.findAll(fullText).map { it.groupValues[1] }.toList()
                .filter { it.lines().size >= 25 && it.contains("<html", ignoreCase = true) }
                .take(2)
            for (html in blocks) {
                val stamp = SimpleDateFormat("ddMM-HHmmss", Locale.US).format(Date())
                val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(60)
                    .orEmpty().ifBlank { "Mini-app $stamp" }
                val record = com.pr4nav.jarvis.artifacts.JarvisArtifactManager.saveArtifact(
                    context = activity,
                    id = "chat-$stamp-${(100..999).random()}",
                    title = title,
                    type = "UI_APP",
                    content = html,
                    fileExtension = "html",
                    summary = "Mini-app saved from chat",
                    tags = listOf("chat", "mini-app")
                )
                container.addView(
                    ArtifactCard(activity, record.title, record.type) {
                        listener.onOpenArtifactFile(record.filePath, record.title, record.type)
                    }
                )
            }
        } catch (_: Exception) { }
    }

    private fun scroll() {
        try {
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        } catch (_: Exception) { }
    }

    private fun voidUnused(@Suppress("UNUSED_PARAMETER") v: Any) { }
}
