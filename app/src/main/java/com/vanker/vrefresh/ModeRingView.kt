package com.vanker.vrefresh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Decorative, original ring. Its value represents measured display Hz, never gameplay FPS. */
internal class ModeRingView(context: Context, private val maxHz: Float, private val currentHz: Float) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = 0x30266AEF; strokeWidth = 10f
    }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = 0xFF3477F6.toInt(); strokeWidth = 10f
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = resources.displayMetrics.density
        val diameter = min(width, height).toFloat()
        val stroke = 8f * scale
        track.strokeWidth = stroke; active.strokeWidth = stroke
        val half = diameter / 2f
        val box = RectF((width - diameter) / 2f + stroke,
            (height - diameter) / 2f + stroke,
            (width + diameter) / 2f - stroke,
            (height + diameter) / 2f - stroke)
        canvas.drawArc(box, 138f, 264f, false, track)
        val progress = (currentHz / maxHz.coerceAtLeast(60f)).coerceIn(0f, 1f)
        canvas.drawArc(box, 138f, 264f * progress, false, active)
    }
}
