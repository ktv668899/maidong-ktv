package com.local.ktv

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.WeakHashMap

/** Applies the original OTT red-to-orange focus fill to remote-control targets. */
object TvFocusStyler {
    private val installed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val fillSuppressed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /**
     * 禁止对该 View 施加焦点/按下填充 —— 用于「大面积展示型表面」。
     *
     * 背景：[install] 会把背景整体替换成不透明的红→橙渐变，作为遥控器焦点/按下的视觉反馈。
     * 这对按钮、Tab、卡片是期望效果；但对铺满屏幕或覆盖播放画面的表面型 View
     * （播放器宿主、视频层、播放触摸层、全屏播放容器、启动加载遮罩、二维码浮层）就是灾难：
     * 只要触摸一次（`state_pressed`）就会用一个纯色层盖住整屏内容，即「纯色遮罩层」。
     *
     * 调用后该 View 保留自身背景且不再被 [install] 处理，平台默认灰色高亮也被关闭。
     *
     * @param focusable 是否顺带让该 View 可被遥控器聚焦（默认 true，与旧的 [install] 行为一致）；
     *   纯触摸捕获层（如 `playerTouchOverlay`）应传 false，否则会抢走遥控器焦点
     *   且自身没有任何焦点视觉反馈。
     */
    fun suppressFill(view: View, focusable: Boolean = true) {
        fillSuppressed.add(view)
        disablePlatformHighlight(view)
        if (focusable && view.isClickable && view.isEnabled && view.visibility == View.VISIBLE) {
            view.isFocusable = true
            view.isFocusableInTouchMode = false
        }
    }

    fun install(view: View) {
        install(view, false)
    }

    /** Force the shared focus fill on platform widgets whose stock background is stateful. */
    fun installAction(view: View) {
        install(view, true)
    }

    private fun install(view: View, force: Boolean) {
        disablePlatformHighlight(view)
        if (!view.isEnabled || view.visibility != View.VISIBLE || !view.isClickable) return
        // 显式标记为「表面型」的 View: 既不填充背景, 也不替调用方强制可聚焦
        // (例如 playerTouchOverlay 刻意要求 isFocusable=false, 否则会抢走遥控器焦点)。
        if (!force && fillSuppressed.contains(view)) return
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        if (!force && view.background?.isStateful == true) return
        if (!installed.add(view) && !force) return
        val normal = view.background ?: ColorDrawable(Color.TRANSPARENT)
        val normalShape = normal as? GradientDrawable
        val normalCornerRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { normalShape?.cornerRadius }.getOrNull()
        } else {
            null
        }
        val normalCornerRadii = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 9's GradientDrawable getter throws when the shape uses a
            // uniform radius and therefore has no per-corner array.
            runCatching { normalShape?.cornerRadii?.clone() }.getOrNull()
        } else {
            null
        }
        fun focusFill() = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(232, 49, 111), Color.rgb(255, 154, 48)),
            ).apply {
                when {
                    normalCornerRadii != null -> cornerRadii = normalCornerRadii.clone()
                    normalCornerRadius != null -> cornerRadius = normalCornerRadius
                    else -> cornerRadius = view.resources.displayMetrics.density * 7f
                }
                setStroke((view.resources.displayMetrics.density + 0.5f).toInt(), Color.argb(150, 255, 255, 255))
            }

        view.background = StateListDrawable().apply {
            // Focus must never retain the normal drawable. Platform Buttons and a
            // few legacy OTT assets use rectangular grey insets which remain visible
            // around a rounded overlay and make the focus target look oversized.
            addState(intArrayOf(android.R.attr.state_focused), focusFill())
            addState(intArrayOf(android.R.attr.state_pressed), focusFill())
            addState(intArrayOf(), normal)
        }
    }

    fun installTree(view: View) {
        disablePlatformHighlight(view)
        // Structural dialog containers own navigation, but are not action targets.
        // Styling them paints the entire option sheet instead of the active option.
        if (view !is ViewGroup) install(view)
        if (view is ViewGroup) repeat(view.childCount) { installTree(view.getChildAt(it)) }
    }

    fun disablePlatformHighlightTree(view: View) {
        disablePlatformHighlight(view)
        if (view is ViewGroup) repeat(view.childCount) {
            disablePlatformHighlightTree(view.getChildAt(it))
        }
    }

    private fun disablePlatformHighlight(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
    }
}
