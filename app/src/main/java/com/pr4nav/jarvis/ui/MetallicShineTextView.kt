package com.pr4nav.jarvis.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import com.pr4nav.jarvis.R

/**
 * Clean typography TextView with Google DM Sans font and an animated left-to-right silver metallic shine.
 * No borders, capsules, or extra spacing — pure natural typography with specular sheen.
 */
class MetallicShineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var linearGradient: LinearGradient? = null
    private val gradientMatrix = Matrix()
    private var translate = 0f
    private var animator: ValueAnimator? = null

    // Silver metal gradient: Slate Silver -> Chrome Silver -> Specular White -> Chrome Silver -> Slate Silver
    private val metallicColors = intArrayOf(
        0xFF94A3B8.toInt(), // Slate Silver
        0xFFE2E8F0.toInt(), // Chrome Light
        0xFFFFFFFF.toInt(), // Specular White Shine
        0xFFE2E8F0.toInt(), // Chrome Light
        0xFF94A3B8.toInt()  // Slate Silver
    )
    private val colorPositions = floatArrayOf(0.0f, 0.35f, 0.5f, 0.65f, 1.0f)

    init {
        includeFontPadding = false
        letterSpacing = 0.02f // Natural DM Sans letter spacing (no extra spacing)
        try {
            val dmSansTf = ResourcesCompat.getFont(context, R.font.dm_sans)
            if (dmSansTf != null) {
                typeface = Typeface.create(dmSansTf, Typeface.BOLD)
            } else {
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
        } catch (_: Exception) {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            val gradientWidth = w.toFloat() * 1.5f
            linearGradient = LinearGradient(
                0f, 0f, gradientWidth, 0f,
                metallicColors,
                colorPositions,
                Shader.TileMode.CLAMP
            )
            paint.shader = linearGradient
            startShineAnimation(w.toFloat())
        }
    }

    private fun startShineAnimation(viewWidth: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(-viewWidth * 1.5f, viewWidth * 2.0f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                translate = it.animatedValue as Float
                gradientMatrix.setTranslate(translate, 0f)
                linearGradient?.setLocalMatrix(gradientMatrix)
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (width > 0 && (animator == null || !animator!!.isRunning)) {
            startShineAnimation(width.toFloat())
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
