package com.pr4nav.jarvis

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.chat.ChatUi
import com.pr4nav.jarvis.llm.AIProvider
import com.pr4nav.jarvis.llm.GeminiCloudLLM
import com.pr4nav.jarvis.llm.GroqClient
import com.pr4nav.jarvis.llm.KiraClient
import com.pr4nav.jarvis.llm.OllamaClient

/**
 * One screen for every provider key: status, current model, edit.
 * Replaces the old per-provider configure dialogs.
 */
class ProviderKeysActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_keys)

        list = findViewById(R.id.keys_list)
        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btn_refresh_keys)?.setOnClickListener { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        for (p in AIProvider.values()) {
            list.addView(providerRow(p))
        }
        val hint = TextView(this).apply {
            text = "Keys stay on-device. Models are fetched live from the provider — nothing is hardcoded."
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(this@ProviderKeysActivity, 14) }
        }
        list.addView(hint)
    }

    private fun providerRow(p: AIProvider): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tool_card)
            val pad = ChatUi.dp(this@ProviderKeysActivity, 12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(this@ProviderKeysActivity, 8) }
        }

        val hasKey = try {
            p.hasKey(this)
        } catch (_: Exception) {
            false
        }
        val dot = View(this).apply {
            val s = ChatUi.dp(this@ProviderKeysActivity, 10)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setBackgroundResource(R.drawable.bg_dot)
            alpha = 1f
            try {
                background?.let {
                    val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(it.mutate())
                    androidx.core.graphics.drawable.DrawableCompat.setTint(
                        wrapped, Color.parseColor(if (hasKey) "#10B981" else "#EF4444")
                    )
                    background = wrapped
                }
            } catch (_: Exception) { }
        }
        row.addView(dot)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(this@ProviderKeysActivity, 10)
            }
        }
        val title = TextView(this).apply {
            text = p.title
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(title)
        val sub = TextView(this).apply {
            val model = try {
                p.currentModel(this@ProviderKeysActivity)
            } catch (_: Exception) {
                ""
            }
            text = (if (hasKey) "key saved" else "no key") + if (model.isNotBlank()) " · $model" else ""
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(sub)
        row.addView(texts)

        val keyBtn = TextView(this).apply {
            text = if (hasKey) "CHANGE" else "ADD KEY"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.bg_btn_action_pill)
            val bp = ChatUi.dp(this@ProviderKeysActivity, 8)
            setPadding(bp * 2, bp, bp * 2, bp)
            isClickable = true
            isFocusable = true
            setOnClickListener { showKeyDialog(p) }
        }
        row.addView(keyBtn)
        return row
    }

    private fun showKeyDialog(p: AIProvider) {
        val current = when (p) {
            AIProvider.KIRA -> KiraClient.getApiKey(this)
            AIProvider.GROQ -> GroqClient.getApiKey(this)
            AIProvider.GEMINI -> GeminiCloudLLM.getApiKey(this)
            AIProvider.OLLAMA -> OllamaClient.getApiKey(this)
        }
        val edit = EditText(this).apply {
            setText(current)
            hint = "${p.title} API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(30, 25, 30, 25)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${p.title} API key")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                val v = edit.text.toString().trim()
                when (p) {
                    AIProvider.KIRA -> KiraClient.setApiKey(this, v)
                    AIProvider.GROQ -> GroqClient.setApiKey(this, v)
                    AIProvider.GEMINI -> GeminiCloudLLM.setApiKey(this, v)
                    AIProvider.OLLAMA -> OllamaClient.setApiKey(this, v)
                }
                Toast.makeText(
                    this,
                    if (v.isNotEmpty()) "${p.title} key saved" else "${p.title} key cleared",
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
