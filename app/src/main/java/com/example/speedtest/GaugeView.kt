package com.example.speedtest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.ln

class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val strokeWidth = 16f * density
    private val rect = RectF()
    private var fraction = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@GaugeView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(40, 255, 255, 255)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@GaugeView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }

    fun setSpeed(mbps: Double) {
        val target = (ln(mbps.coerceAtLeast(0.0) + 1.0) / ln(501.0))
            .toFloat()
            .coerceIn(0f, 1f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 800
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = strokeWidth / 2f + 4f * density
        rect.set(pad, pad, w - pad, h - pad)
        progressPaint.shader = SweepGradient(
            w / 2f,
            h / 2f,
            intArrayOf(
                Color.parseColor("#22D3EE"),
                Color.parseColor("#3B82F6"),
                Color.parseColor("#A855F7"),
                Color.parseColor("#22D3EE")
            ),
            null
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(rect, 135f, 270f, false, trackPaint)
        if (fraction > 0f) {
            canvas.drawArc(rect, 135f, 270f * fraction, false, progressPaint)
        }
    }
}
