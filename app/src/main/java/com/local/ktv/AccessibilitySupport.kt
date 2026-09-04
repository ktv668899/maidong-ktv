package com.local.ktv

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal fun View.setAccessibleFocus(marker: String, spokenLabel: String) {
    setTag(R.id.tag_focus_marker, marker)
    contentDescription = spokenLabel
}

internal fun View.focusMarker(): String? = getTag(R.id.tag_focus_marker) as? String

internal fun ViewGroup.visibleTextLabel(): String = buildList {
    fun collect(view: View) {
        if (size >= 4 || view.visibility != View.VISIBLE) return
        when (view) {
            is TextView -> view.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            is ViewGroup -> repeat(view.childCount) { collect(view.getChildAt(it)) }
        }
    }
    collect(this@visibleTextLabel)
}.distinct().joinToString("，")
