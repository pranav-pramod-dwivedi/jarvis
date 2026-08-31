package com.pr4nav.jarvis.gui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Stark HUD & Siri-style Multi-Phase Glowing Audio Waveform Visualizer.
 *
 * Renders fluid, multi-layered anti-aliased sine waves reactive to real-time mic amplitude
 * or assistant speech playback with futuristic Arc-Reactor cyan & indigo gradients.
 */
class StarkWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePath1 = Path()
    private val wavePath2 = Path()
    private val wavePath3 = Path()

    private val wavePaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val wavePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        strokeCap = Paint.Cap.ROUND
    }

    private val wavePaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var phase1 = 0.0f
    private var phase2 = 0.0f
    private var phase3 = 0.0f

    private var currentAmplitude = 0.15f
    private var targetAmplitude = 0.15f
    private var isActive = true

    init {
        // Run continuous refresh loop
        post(object : Runnable {
            override fun run() {
                if (isShown) {
                    phase1 += 0.08f
                    phase2 += 0.05f
                    phase3 += 0.11f

                    // Smooth amplitude damping
                    currentAmplitude += (targetAmplitude - currentAmplitude) * 0.25f

                    invalidate()
                }
                postDelayed(this, 16) // ~60fps
            }
        })
    }

    fun setAmplitude(rms: Float) {
        // Normalize RMS (~ 0 to 5000) to 0.0f - 1.0f range
        val norm = ((rms - 100f) / 3000f).coerceIn(0.12f, 1.0f)
        targetAmplitude = norm
    }

    fun setActive(active: Boolean) {
        isActive = active
        if (!active) {
            targetAmplitude = 0.05f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val cyan = Color.parseColor("#00E5FF")
            val blue = Color.parseColor("#0284C7")
            val indigo = Color.parseColor("#818CF8")
            val transparent = Color.TRANSPARENT

            wavePaint1.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
                intArrayOf(transparent, cyan, blue, cyan, transparent),
                floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1.0f),
                Shader.TileMode.CLAMP
            )

            wavePaint2.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
                intArrayOf(transparent, indigo, cyan, indigo, transparent),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )

            wavePaint3.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
                intArrayOf(transparent, Color.WHITE, cyan, Color.WHITE, transparent),
                floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerY = h / 2f
        val maxAmp = (h / 2.2f) * currentAmplitude

        // Generate Path 1 (Primary Cyan Arc Wave)
        wavePath1.reset()
        wavePath1.moveTo(0f, centerY)
        var x = 0f
        val step = 4f
        while (x <= w) {
            val progress = x / w
            // Hanning / Bell envelope to taper ends
            val envelope = sin(progress * PI).toFloat()
            val y = centerY + sin((x * 0.025f) + phase1).toFloat() * maxAmp * envelope
            wavePath1.lineTo(x, y)
            x += step
        }
        canvas.drawPath(wavePath1, wavePaint1)

        // Generate Path 2 (Indigo harmonic wave)
        wavePath2.reset()
        wavePath2.moveTo(0f, centerY)
        x = 0f
        while (x <= w) {
            val progress = x / w
            val envelope = sin(progress * PI).toFloat()
            val y = centerY + sin((x * 0.038f) - phase2).toFloat() * (maxAmp * 0.75f) * envelope
            wavePath2.lineTo(x, y)
            x += step
        }
        canvas.drawPath(wavePath2, wavePaint2)

        // Generate Path 3 (Fast particle frequency wave)
        wavePath3.reset()
        wavePath3.moveTo(0f, centerY)
        x = 0f
        while (x <= w) {
            val progress = x / w
            val envelope = sin(progress * PI).toFloat()
            val y = centerY + sin((x * 0.055f) + phase3).toFloat() * (maxAmp * 0.5f) * envelope
            wavePath3.lineTo(x, y)
            x += step
        }
        canvas.drawPath(wavePath3, wavePaint3)
    }
}
