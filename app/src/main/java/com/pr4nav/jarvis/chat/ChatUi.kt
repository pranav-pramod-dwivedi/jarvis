package com.pr4nav.jarvis.chat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/** Shared builders for the agent chat surface. */
object ChatUi {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun sp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.scaledDensity /
        ctx.resources.displayMetrics.density

    fun icon(ctx: Context, resId: Int, colorHex: String, sizeDp: Int = 20): ImageView {
        val iv = ImageView(ctx)
        try {
            var d: Drawable? = ContextCompat.getDrawable(ctx, resId)
            if (d != null) {
                d = DrawableCompat.wrap(d.mutate())
                DrawableCompat.setTint(d, Color.parseColor(colorHex))
                iv.setImageDrawable(d)
            }
        } catch (_: Exception) { }
        iv.layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        return iv
    }

    fun label(
        ctx: Context,
        text: String,
        colorHex: String,
        sizeSp: Float,
        bold: Boolean = false
    ): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor(colorHex))
            textSize = sizeSp
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun mono(ctx: Context, text: String, colorHex: String, sizeSp: Float): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor(colorHex))
            textSize = sizeSp
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> String.format("%.1fs", ms / 1000f)
            else -> String.format("%dm %ds", ms / 60_000, (ms % 60_000) / 1000)
        }
    }
}
