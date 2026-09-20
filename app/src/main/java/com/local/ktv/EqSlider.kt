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
 * 横向控制杆 —— 「调音」弹窗的音量与均衡器频段共用。
 *
 * 交互按两种输入方式分别设计（对应电视遥控与触控）：
 *
 * **遥控器**：
 * - 焦点在控件上时，左右/上下键**不**改变数值 —— 先让焦点在控件之间正常移动；
 * - 按「确认」键进入**调节态**（控件出现高亮描边），此时左右/上下键才改变数值；
 * - 再按一次「确认」退出调节态；调节态下 BACK 也只退出调节态，不关闭弹窗；
 * - 失去焦点时自动退出调节态。
 *
 * **触控**：
 * - 必须按住滑块（控件点）才能拖动，点轨道空白处**不会**跳变数值；
 * - 在滑块上按下后手指移出控件范围仍可继续拖动，直到抬起。
 *
 * 数值范围由 [setRange] 给出；[showZeroLine] 为 true 时（均衡器频段）会画出 0 参考线，
 * 并从参考线填充到当前值，便于一眼看出提升/衰减。
 */
class EqSlider(context: Context) : View(context) {

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
        strokeWidth = 2.5f
    }
    private val adjustingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 106)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val density = resources.displayMetrics.density
    private val trackHeight = 8f * density
    private val thumbRadius = 11f * density
    /** 触摸命中滑块的水平容差；只有按住这个范围才允许拖动。 */
    private val thumbTouchSlop = 26f * density

    private var minValue = 0f
    private var maxValue = 100f
    private var steps = 100
    private var level = 0f
    private var showZeroLine = false

    /** 是否处于遥控器「调节态」（按确认键进入/退出）。 */
    var adjusting: Boolean = false
        private set

    /** 值变化回调（fromUser=true 表示用户操作触发）。 */
    var onLevelChanged: ((level: Float, fromUser: Boolean) -> Unit)? = null

    /** 一次调节结束（退出调节态 / 抬手 / 失焦）时回调，便于上层落盘。 */
    var onInteractionFinished: (() -> Unit)? = null

    private var dragging = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        // 自绘控件：禁止 TvFocusStyler 用不透明渐变覆盖背景（自己画焦点/调节描边）。
        TvFocusStyler.suppressFill(this)
    }

    fun setRange(minValue: Float, maxValue: Float, steps: Int, zeroLine: Boolean = false) {
        this.minValue = minValue
        this.maxValue = maxValue
        this.steps = steps.coerceAtLeast(1)
        this.showZeroLine = zeroLine
        invalidate()
    }

    fun level(): Float = level

    fun setLevel(value: Float, notify: Boolean = false) {
        val clamped = value.coerceIn(minValue, maxValue)
        if (clamped == level) {
            invalidate()
            return
        }
        level = clamped
        invalidate()
        if (notify) onLevelChanged?.invoke(level, true)
    }

    private fun stepSize(): Float = (maxValue - minValue) / steps

    private fun valueToRatio(value: Float): Float =
        ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)

    private fun ratioToValue(ratio: Float): Float {
        val raw = minValue + ratio.coerceIn(0f, 1f) * (maxValue - minValue)
        // 吸附到刻度上，保证 0 一定落在某档（均衡器 0dB / 音量整数值）。
        val ticks = Math.round((raw - minValue) / stepSize())
        return (minValue + ticks * stepSize()).coerceIn(minValue, maxValue)
    }

    private fun trackLeft(): Float = paddingLeft + thumbRadius
    private fun trackRight(): Float = width - paddingRight - thumbRadius
    private fun thumbX(): Float =
        trackLeft() + valueToRatio(level) * (trackRight() - trackLeft())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = trackLeft()
        val right = trackRight()
        if (right <= left) return
        val centerY = height / 2f
        val radius = trackHeight / 2f

        canvas.drawRoundRect(
            RectF(left, centerY - radius, right, centerY + radius),
            radius,
            radius,
            trackPaint,
        )

        // 0 参考线（均衡器频段）：从参考线填充到当前值。
        val zeroRatio = if (showZeroLine) ((0f - minValue) / (maxValue - minValue)).coerceIn(0f, 1f) else 0f
        val zeroX = left + zeroRatio * (right - left)
        if (showZeroLine) {
            canvas.drawLine(zeroX, centerY - trackHeight, zeroX, centerY + trackHeight, zeroPaint)
        }
        val valueX = thumbX()
        val fillStart = minOf(zeroX, valueX)
        val fillEnd = maxOf(zeroX, valueX)
        if (fillEnd - fillStart > 1f) {
            canvas.drawRoundRect(
                RectF(fillStart, centerY - radius, fillEnd, centerY + radius),
                radius,
                radius,
                fillPaint,
            )
        }

        canvas.drawCircle(valueX, centerY, thumbRadius, thumbPaint)

        if (isFocused) {
            val inset = 2f * density
            canvas.drawRoundRect(
                RectF(inset, inset, width - inset, height - inset),
                8f * density,
                8f * density,
                if (adjusting) adjustingPaint else focusPaint,
            )
        }
    }

    /** 触摸点是否落在滑块（控件点）上。 */
    private fun hitsThumb(x: Float, y: Float): Boolean {
        if (y < 0 || y > height) return false
        return Math.abs(x - thumbX()) <= thumbTouchSlop
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 触控优化：只有按住滑块才开始拖动，点空白轨道不跳变数值。
                if (!hitsThumb(event.x, event.y)) return false
                dragging = true
                isPressed = true
                if (!isFocused) requestFocus()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val span = trackRight() - trackLeft()
                if (span <= 0f) return true
                val ratio = (event.x - trackLeft()) / span
                val next = ratioToValue(ratio)
                if (next != level) {
                    level = next
                    invalidate()
                    onLevelChanged?.invoke(level, true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                isPressed = false
                performClick()
                onInteractionFinished?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun step(delta: Int): Boolean {
        val current = Math.round((level - minValue) / stepSize())
        val next = (current + delta).coerceIn(0, steps)
        if (next == current) return false
        level = (minValue + next * stepSize()).coerceIn(minValue, maxValue)
        invalidate()
        onLevelChanged?.invoke(level, true)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirm) {
            // 按确认键：进入 / 退出调节态。
            adjusting = !adjusting
            invalidate()
            if (!adjusting) onInteractionFinished?.invoke()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && adjusting) {
            adjusting = false
            invalidate()
            onInteractionFinished?.invoke()
            return true
        }
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (!adjusting) {
            // 未进入调节态：不消耗方向键，让焦点在控件之间正常移动。
            return super.onKeyDown(keyCode, event)
        }
        step(delta)
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && adjusting) {
            adjusting = false
            invalidate()
            onInteractionFinished?.invoke()
        }
    }
}
