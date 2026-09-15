package com.pr4nav.jarvis

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.pr4nav.jarvis.chat.ChatUi
import com.pr4nav.jarvis.llm.KiraClient
import com.pr4nav.jarvis.llm.KiraModelPicker
import com.pr4nav.jarvis.router.RouteEngine
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher

/**
 * Whole routing harness in one screen:
 * engine fallback route, Kira model + solo mode, latency-aware auto-router
 * with its rules, and measured per-model latency stats.
 */
class RouteHarnessActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_harness)

        list = findViewById(R.id.harness_list)
        subtitle = findViewById(R.id.harness_subtitle)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btn_refresh_harness)?.setOnClickListener { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val route = UnifiedAssistantDispatcher.getRoute(this)
        subtitle.text = "Route ${UnifiedAssistantDispatcher.routeShort(route)} · ${KiraClient.modelLabel(displayModel())}"

        section("ENGINE FALLBACK ROUTE")
        val presets = UnifiedAssistantDispatcher.ROUTE_PRESETS
        presets.forEach { (name, engines) ->
            list.addView(presetRow(name, engines, engines == route))
        }

        section("KIRA MODEL")
        list.addView(modelRow())
        list.addView(switchRow(
            title = "Solo model (no cascade)",
            desc = "Run the selected model only — never fall back",
            checked = KiraClient.isSoloModel(this),
            onChange = {
                KiraClient.setSoloModel(this, it)
                render()
            }
        ))

        section("KIRA AUTO-ROUTER")
        list.addView(switchRow(
            title = "Auto-router",
            desc = "Classify each task to the fastest-fit model",
            checked = KiraClient.isAutoRouter(this),
            onChange = {
                KiraClient.setAutoRouter(this, it)
                render()
            }
        ))
        list.addView(ruleRow("Casual chat", "→ Kira Mini 1.0 · fastest, least reasoning"))
        list.addView(ruleRow("Code · build · debug", "→ GLM 5.3 Free · deepest reasoning"))
        list.addView(ruleRow("Everything else", "→ Qwen 3.8 Flash · balanced"))

        section("MODEL LATENCY")
        val stats = KiraClient.getModelStats(this)
        if (stats.isEmpty()) {
            list.addView(hintRow("No measurements yet — latencies appear after queries run."))
        } else {
            val ordered = stats.entries.sortedByDescending { it.value.count }
            for ((id, s) in ordered) {
                list.addView(statRow(id, s))
            }
            list.addView(actionButton("CLEAR STATS") {
                KiraClient.clearModelStats(this)
                render()
                Toast.makeText(this, "Latency stats cleared", Toast.LENGTH_SHORT).show()
            })
        }

        section("CONNECTION PROBE")
        list.addView(hintRow("Queries /models + a 1-token chat POST with your stored key. Diagnoses 404/401/402 with the exact server message."))
        list.addView(actionButton("TEST CONNECTION", danger = false) {
            Toast.makeText(this, "Probing Kira endpoints…", Toast.LENGTH_SHORT).show()
            KiraClient.probeEndpoints(this) { res ->
                runOnUiThread { showProbeReport(res) }
            }
        })
        list.addView(actionButton("PROVIDER KEYS", danger = false) {
            startActivity(android.content.Intent(this, ProviderKeysActivity::class.java))
        })
    }

    private fun showProbeReport(res: KiraClient.ProbeResult) {
        val sb = StringBuilder()
        sb.appendLine("KIRA ENDPOINT PROBE")
        sb.appendLine("key: ${if (res.keyPresent) "present" else "MISSING — add it in Kira AI settings"}")
        sb.appendLine("models: HTTP ${res.modelsCode} · ${res.modelsCount} listed")
        if (res.error != null) {
            sb.appendLine("probe error: ${res.error}")
        }
        val current = KiraClient.getModel(this)
        val effective = if (current == KiraClient.MODEL_AUTO) KiraClient.lastAutoModel() else current
        sb.appendLine("chat(model=$effective): HTTP ${res.chatCode} in ${res.chatMs}ms")
        if (res.chatBody.isNotBlank()) {
            sb.appendLine("body: ${res.chatBody}")
        }
        if (res.platformIds.isNotEmpty()) {
            sb.appendLine("---")
            val checkIds = (listOf(effective) + KiraClient.FREE_MODEL_CASCADE).distinct()
            for (id in checkIds) {
                if (res.platformIds.contains(id)) {
                    sb.appendLine("$id: IN LIST")
                } else {
                    val near = KiraClient.nearestPlatformId(id, res.platformIds)
                    sb.appendLine("$id: NOT IN LIST" + (if (near != null) " → did you mean '$near'?" else ""))
                }
            }
        }
        sb.appendLine("---")
        sb.appendLine(
            when {
                !res.keyPresent -> "verdict: no API key stored — chat will 401. Add the key first."
                res.chatCode in 200..299 -> "verdict: chat endpoint works with '$effective'."
                res.chatCode == 401 -> "verdict: key rejected — re-enter the Kira API key."
                res.chatCode == 402 -> "verdict: wallet empty — top up at kiraai.vn."
                res.chatCode == 404 -> "verdict: endpoint/model not found — server message above; try the suggested id."
                res.chatCode == 429 -> "verdict: rate limited — wait a minute and retry."
                else -> "verdict: HTTP ${res.chatCode} — server message above."
            }
        )

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                ChatUi.dp(this@RouteHarnessActivity, 320)
            )
        }
        val tv = android.widget.TextView(this).apply {
            text = sb.toString().trim()
            setTextColor(android.graphics.Color.parseColor("#E6EDF6"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(4, 4, 4, 4)
        }
        scroll.addView(tv)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Connection probe")
            .setView(scroll)
            .setPositiveButton("Copy report") { _, _ ->
                try {
                    val cb = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("Kira probe", sb.toString().trim()))
                    Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) { }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun displayModel(): String {
        val raw = KiraClient.getModel(this)
        return if (raw == KiraClient.MODEL_AUTO) KiraClient.lastAutoModel() else raw
    }

    // ── row builders ──────────────────────────────────────────────────────────

    private fun section(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(this@RouteHarnessActivity, 18) }
        }
        list.addView(tv)
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tool_card)
            val p = ChatUi.dp(this@RouteHarnessActivity, 12)
            setPadding(p, ChatUi.dp(this@RouteHarnessActivity, 10), p, ChatUi.dp(this@RouteHarnessActivity, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(this@RouteHarnessActivity, 8) }
        }
    }

    private fun presetRow(name: String, engines: List<RouteEngine>, active: Boolean): LinearLayout {
        val row = card()
        row.isClickable = true
        row.isFocusable = true
        val dot = View(this).apply {
            val s = ChatUi.dp(this@RouteHarnessActivity, 10)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setBackgroundResource(R.drawable.bg_dot)
            alpha = if (active) 1f else 0.25f
        }
        row.addView(dot)
        val tv = TextView(this).apply {
            text = name + if (active) "  ·  active" else ""
            setTextColor(Color.parseColor(if (active) "#F1F5F9" else "#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            if (active) typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(this@RouteHarnessActivity, 10)
            }
        }
        row.addView(tv)
        row.addView(ChatUi.icon(this,
            if (active) R.drawable.ic_check else R.drawable.ic_chev,
            if (active) "#10B981" else "#475569", 16))
        row.setOnClickListener {
            UnifiedAssistantDispatcher.setRoute(this, engines)
            Toast.makeText(this, "Route: ${UnifiedAssistantDispatcher.routeShort(engines)}", Toast.LENGTH_SHORT).show()
            render()
        }
        return row
    }

    private fun modelRow(): LinearLayout {
        val row = card()
        row.addView(ChatUi.icon(this, R.drawable.ic_bolt, "#38BDF8", 22))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(this@RouteHarnessActivity, 10)
            }
        }
        val raw = KiraClient.getModel(this)
        val title = TextView(this).apply {
            text = if (raw == KiraClient.MODEL_AUTO) {
                "Auto · last pick ${KiraClient.modelLabel(KiraClient.lastAutoModel())}"
            } else {
                KiraClient.modelLabel(raw)
            }
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(title)
        val sub = TextView(this).apply {
            text = if (raw == KiraClient.MODEL_AUTO) "router decides per task" else raw
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(sub)
        row.addView(texts)
        val change = TextView(this).apply {
            text = "CHANGE"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_btn_action_pill)
            val bp = ChatUi.dp(this@RouteHarnessActivity, 8)
            setPadding(bp * 2, bp, bp * 2, bp)
            isClickable = true
            isFocusable = true
            setOnClickListener { KiraModelPicker.show(this@RouteHarnessActivity) { render() } }
        }
        row.addView(change)
        return row
    }

    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = card()
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(t)
        val d = TextView(this).apply {
            text = desc
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(d)
        row.addView(texts)
        val sw = SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        return row
    }

    private fun ruleRow(signal: String, target: String): LinearLayout {
        val row = card()
        row.addView(ChatUi.icon(this, R.drawable.ic_brain, "#A78BFA", 18))
        val tv = TextView(this).apply {
            text = "$signal  $target"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(this@RouteHarnessActivity, 10)
            }
        }
        row.addView(tv)
        return row
    }

    private fun statRow(id: String, s: KiraClient.ModelStats): LinearLayout {
        val row = card()
        row.addView(ChatUi.icon(this, R.drawable.ic_clock, "#F0ABFC", 18))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(this@RouteHarnessActivity, 10)
            }
        }
        val t = TextView(this).apply {
            text = KiraClient.modelLabel(id)
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(t)
        val d = TextView(this).apply {
            text = "last ${formatMs(s.lastMs)} · avg ${formatMs(s.avgMs)} · ${s.count} runs"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(d)
        row.addView(texts)
        return row
    }

    private fun hintRow(text: String): LinearLayout {
        val row = card()
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(tv)
        return row
    }

    private fun actionButton(text: String, danger: Boolean = true, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(if (danger) "#EF4444" else "#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_btn_action_pill)
            val bp = ChatUi.dp(this@RouteHarnessActivity, 10)
            setPadding(bp * 2, bp, bp * 2, bp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(this@RouteHarnessActivity, 8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun formatMs(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> String.format("%.1fs", ms / 1000f)
            else -> String.format("%dm %ds", ms / 60_000, (ms % 60_000) / 1000)
        }
    }
}
