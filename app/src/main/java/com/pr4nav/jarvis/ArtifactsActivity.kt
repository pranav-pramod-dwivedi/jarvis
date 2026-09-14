package com.pr4nav.jarvis

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.artifacts.ArtifactRecord
import com.pr4nav.jarvis.artifacts.JarvisArtifactManager
import com.pr4nav.jarvis.browser.JarvisBrowserAppManager
import com.pr4nav.jarvis.chat.ChatUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Artifacts tab: every saved artifact and generated mini-app in one place.
 * Tapping Open launches it in a fresh tab (JarvisBrowser for UI apps).
 */
class ArtifactsActivity : AppCompatActivity() {

    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_artifacts)

        listBox = findViewById(R.id.artifacts_list)
        emptyView = findViewById(R.id.artifacts_empty)
        countView = findViewById(R.id.artifacts_count)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btn_refresh_artifacts)?.setOnClickListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        listBox.removeAllViews()
        thread {
            val items = try {
                JarvisArtifactManager.listArtifacts(this)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                countView.text = if (items.isEmpty()) "No artifacts yet" else "${items.size} saved"
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                for (a in items) listBox.addView(row(a))
            }
        }
    }

    private fun row(a: ArtifactRecord): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tool_card)
            val p = ChatUi.dp(this@ArtifactsActivity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ChatUi.dp(this@ArtifactsActivity, 8) }
        }

        val iconRes = when (a.type.uppercase()) {
            "UI_APP" -> R.drawable.ic_window
            "CODE" -> R.drawable.ic_code
            "DASHBOARD", "CHART", "SIMULATION" -> R.drawable.ic_layers
            else -> R.drawable.ic_file
        }
        val icon: ImageView = ChatUi.icon(this, iconRes, "#10B981", 24)
        row.addView(icon)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChatUi.dp(this@ArtifactsActivity, 10)
            }
        }
        val title = TextView(this).apply {
            text = a.title.ifBlank { a.id }
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(title)
        val date = SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(a.updatedAt))
        val sub = TextView(this).apply {
            text = "${a.type.lowercase().replace('_', ' ')} · $date"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        texts.addView(sub)
        row.addView(texts)

        val open = TextView(this).apply {
            text = "Open"
            setTextColor(Color.parseColor("#06281C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            try {
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@ArtifactsActivity, R.drawable.bg_active_pill)
            } catch (_: Exception) {
                setBackgroundColor(Color.parseColor("#10B981"))
            }
            val bp = ChatUi.dp(this@ArtifactsActivity, 8)
            setPadding(bp * 2, bp, bp * 2, bp)
            isClickable = true
            isFocusable = true
            setOnClickListener { openArtifact(a) }
        }
        row.addView(open)

        val del = TextView(this).apply {
            text = "Delete"
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val bp = ChatUi.dp(this@ArtifactsActivity, 8)
            setPadding(bp, bp, bp, bp)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                thread {
                    val ok = JarvisArtifactManager.deleteArtifact(this@ArtifactsActivity, a.id)
                    runOnUiThread {
                        Toast.makeText(this@ArtifactsActivity,
                            if (ok) "Artifact deleted" else "Delete failed",
                            Toast.LENGTH_SHORT).show()
                        if (ok) load()
                    }
                }
            }
        }
        val delWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = ChatUi.dp(this@ArtifactsActivity, 4) }
        }
        delWrap.addView(del)
        row.addView(delWrap)
        return row
    }

    private fun openArtifact(a: ArtifactRecord) {
        try {
            // 1. Live browser app with this id → open directly.
            val apps = try { JarvisBrowserAppManager.listApps(this) } catch (_: Exception) { emptyList() }
            if (apps.any { it.id == a.id }) {
                com.pr4nav.jarvis.browser.JarvisBrowserActivity.launch(this, a.id)
                return
            }
            // 2. HTML file on disk → import as a temporary app and open.
            val f = File(a.filePath)
            if (f.exists() && f.extension.lowercase() == "html") {
                val html = f.readText()
                val app = JarvisBrowserAppManager.createApp(
                    ctx = this,
                    appId = "artifact-${a.id}",
                    title = a.title,
                    description = a.summary,
                    html = html,
                    isTemporary = true
                )
                com.pr4nav.jarvis.browser.JarvisBrowserActivity.launch(this, app.id)
                return
            }
            // 3. Anything else → open the file manager.
            startActivity(android.content.Intent(this, BrowserActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Open failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
