package com.local.ktv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Full-screen volume indicator modelled after the original KTV circular overlay. */
class CircularVolumeView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val arcBounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var volume = 0

    fun setVolume(value: Int) {
        volume = value.coerceIn(0, 100)
        contentDescription = "音量 $volume"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (154f * density).toInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val center = size / 2f
        val outerWidth = 12f * density
        val progressWidth = 12f * density
        val radius = center - outerWidth

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(48, 39, 102)
        canvas.drawCircle(center, center, radius - progressWidth * 0.15f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = outerWidth
        paint.color = Color.rgb(47, 37, 106)
        canvas.drawCircle(center, center, radius, paint)

        val ringRadius = radius - outerWidth * 0.52f
        arcBounds.set(
            center - ringRadius,
            center - ringRadius,
            center + ringRadius,
            center + ringRadius,
        )
        paint.strokeWidth = progressWidth
        paint.color = Color.rgb(60, 51, 111)
        canvas.drawArc(arcBounds, -90f, 360f, false, paint)
        paint.color = Color.rgb(151, 155, 143)
        canvas.drawArc(arcBounds, -90f, volume * 3.6f, false, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(220, 220, 214)
        paint.textSize = 30f * density
        canvas.drawText(volume.toString(), center, center - 3f * density, paint)
        paint.textSize = 20f * density
        canvas.drawText("音量", center, center + 38f * density, paint)
    }
}
