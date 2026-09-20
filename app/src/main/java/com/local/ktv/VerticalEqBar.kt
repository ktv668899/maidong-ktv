package com.local.ktv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * 均衡器竖向控制杆。
 *
 * Android 自带的 `SeekBar` 只支持横向，所以这里自绘一个竖向滑杆：
 * - 触摸点击/拖动定位；
 * - 遥控器上/下键步进（按住可连续微调）；
 * - 获得焦点时描一圈橙色边框（与 App 的 TV 焦点风格一致）。
 *
 * 数值单位是 dB，范围由 [setRange] 给出（取自设备真实支持的增益范围）。
 * 绘制中包含 0 dB 基准线，并从基准线填充到当前值，便于一眼看出提升/衰减。
 */
class VerticalEqBar(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 184, 74)
        style = Paint.Style.FILL
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1.5f
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 152, 48)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val density = resources.displayMetrics.density
    private var minDb = -15f
    private var maxDb = 15f
    private var steps = 30
    private var level = 0f

    /** 值变化回调（含 fromUser 标记，用于区分初始化与用户操作）。 */
    var onLevelChanged: ((level: Float, fromUser: Boolean) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        // 自绘控件：禁止 TvFocusStyler 用不透明渐变覆盖背景（自己画焦点框）。
        TvFocusStyler.suppressFill(this)
    }

    fun setRange(minDb: Float, maxDb: Float, steps: Int) {
        this.minDb = minDb
        this.maxDb = maxDb
        this.steps = steps.coerceAtLeast(1)
        invalidate()
    }

    fun level(): Float = level

    /** 设置当前值；notify=false 用于初始化（不触发回调）。 */
    fun setLevel(value: Float, notify: Boolean = false) {
        val clamped = value.coerceIn(minDb, maxDb)
        if (clamped == level) {
            invalidate()
            return
        }
        level = clamped
        invalidate()
        if (notify) onLevelChanged?.invoke(level, true)
    }

    /** 按刻度步进取值，保证 0 dB 一定落在某个刻度上。 */
    private fun stepDb(): Float = (maxDb - minDb) / steps

    private fun progressToLevel(progress: Int): Float =
        (minDb + progress * stepDb()).coerceIn(minDb, maxDb)

    private fun levelToProgress(value: Float): Int =
        Math.round((value - minDb) / stepDb()).coerceIn(0, steps)

    private fun levelFromY(y: Float): Float {
        val usable = height - paddingTop - paddingBottom
        if (usable <= 0) return level
        val ratio = 1f - ((y - paddingTop) / usable).coerceIn(0f, 1f)
        return minDb + ratio * (maxDb - minDb)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val trackWidth = 10f * density
        val centerX = width / 2f
        val top = paddingTop + 6f * density
        val bottom = height - paddingBottom - 6f * density
        if (bottom <= top) return
        val radius = trackWidth / 2f

        canvas.drawRoundRect(
            RectF(centerX - radius, top, centerX + radius, bottom),
            radius,
            radius,
            trackPaint,
        )

        val zeroRatio = ((0f - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
        val zeroY = bottom - zeroRatio * (bottom - top)
        canvas.drawLine(centerX - radius * 1.8f, zeroY, centerX + radius * 1.8f, zeroY, zeroPaint)

        val valueRatio = ((level - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
        val valueY = bottom - valueRatio * (bottom - top)
        val fillTop = minOf(zeroY, valueY)
        val fillBottom = maxOf(zeroY, valueY)
        if (fillBottom - fillTop > 1f) {
            canvas.drawRoundRect(
                RectF(centerX - radius, fillTop, centerX + radius, fillBottom),
                radius,
                radius,
                fillPaint,
            )
        }

        canvas.drawCircle(centerX, valueY, trackWidth * 0.85f, thumbPaint)

        if (isFocused) {
            val inset = 1.5f * density
            canvas.drawRoundRect(
                RectF(inset, inset, width - inset, height - inset),
                8f * density,
                8f * density,
                focusPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!isFocused) requestFocus()
                val target = levelFromY(event.y)
                val snapped = progressToLevel(levelToProgress(target))
                if (snapped != level) {
                    level = snapped
                    invalidate()
                    onLevelChanged?.invoke(level, true)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val progress = levelToProgress(level)
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 1
            KeyEvent.KEYCODE_DPAD_DOWN -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        val next = (progress + delta).coerceIn(0, steps)
        if (next == progress) {
            // 已到量程端点：放行按键，让焦点能离开滑杆（否则遥控器会困在这里出不去）。
            return super.onKeyDown(keyCode, event)
        }
        level = progressToLevel(next)
        invalidate()
        onLevelChanged?.invoke(level, true)
        return true
    }
}
