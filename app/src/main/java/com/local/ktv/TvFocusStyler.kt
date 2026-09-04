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
    private val preservedBackgrounds = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** Preserve image/video backgrounds while suppressing Android's grey focus halo. */
    fun preserveBackground(view: View) {
        preservedBackgrounds.add(view)
        disablePlatformHighlight(view)
        if (view.isClickable && view.isEnabled && view.visibility == View.VISIBLE) {
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
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        if (!force && (preservedBackgrounds.contains(view) || view.background?.isStateful == true)) return
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
