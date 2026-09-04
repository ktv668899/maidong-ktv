package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Switch

data class SettingsEntry(
    val icon: Int,
    val title: String,
    val subtitle: String = "",
    val actionText: String = "设置",
    val checked: Boolean? = null,
    val action: () -> Unit,
)

class SettingsListAdapter(
    private val context: Context,
    private val entries: List<SettingsEntry>,
    private val topFocusId: Int = View.NO_ID,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density
    private val actionIds = IntArray(entries.size) { View.generateViewId() }
    val firstActionId: Int
        get() = actionIds.firstOrNull() ?: View.NO_ID

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): Any = entries[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val entry = entries[position]
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            setBackgroundResource(R.drawable.ott_bg_item)
            isFocusable = false
            isClickable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

            addView(ImageView(context).apply {
                setImageResource(entry.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(28), dp(28)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(entry.title, 16, Color.WHITE), LinearLayout.LayoutParams(-1, 0, 1f))
                if (entry.subtitle.isNotEmpty()) {
                    addView(label(entry.subtitle, 14, Color.rgb(193, 193, 193)), LinearLayout.LayoutParams(-1, 0, 1f))
                }
            }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(10) })

            if (entry.checked != null) {
                addView(Switch(context).apply {
                    id = actionIds[position]
                    nextFocusUpId = if (position == 0 && topFocusId != View.NO_ID) {
                        topFocusId
                    } else {
                        actionIds[(position - 1).coerceAtLeast(0)]
                    }
                    nextFocusDownId = actionIds[(position + 1).coerceAtMost(entries.lastIndex)]
                    isChecked = entry.checked
                    showText = false
                    setAccessibleFocus(
                        "focus:settings:${entry.title}",
                        "${entry.title}${entry.subtitle.takeIf(String::isNotEmpty)?.let { "，$it" }.orEmpty()}，${if (entry.checked) "已开启" else "已关闭"}",
                    )
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setOnClickListener {
                        entry.action()
                        post { requestFocus() }
                    }
                    TvFocusStyler.installAction(this)
                }, LinearLayout.LayoutParams(dp(70), dp(42)))
            } else {
                addView(label(entry.actionText, 16, Color.WHITE).apply {
                    id = actionIds[position]
                    nextFocusUpId = if (position == 0 && topFocusId != View.NO_ID) {
                        topFocusId
                    } else {
                        actionIds[(position - 1).coerceAtLeast(0)]
                    }
                    nextFocusDownId = actionIds[(position + 1).coerceAtMost(entries.lastIndex)]
                    setAccessibleFocus(
                        "focus:settings:${entry.title}",
                        "${entry.title}${entry.subtitle.takeIf(String::isNotEmpty)?.let { "，$it" }.orEmpty()}，${entry.actionText}",
                    )
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_btn_glass)
                    isFocusable = true
                    isFocusableInTouchMode = false
                    isClickable = true
                    setOnClickListener { entry.action() }
                    TvFocusStyler.install(this)
                }, LinearLayout.LayoutParams(dp(130), dp(33)))
            }
        }
    }

    fun requestInitialFocus(list: ListView, onTargetReady: (View) -> Unit = {}) {
        requestFocusAt(list, 0, onTargetReady)
    }

    fun positionForMarker(marker: String): Int? {
        val prefix = "focus:settings:"
        if (!marker.startsWith(prefix)) return null
        val title = marker.removePrefix(prefix)
        return entries.indexOfFirst { it.title == title }.takeIf { it >= 0 }
    }

    fun requestFocusAt(list: ListView, position: Int, onTargetReady: (View) -> Unit = {}) {
        if (position !in entries.indices) return
        list.itemsCanFocus = true
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        list.setSelection(position)
        list.post {
            val rowIndex = position - list.firstVisiblePosition
            val row = list.getChildAt(rowIndex) as? ViewGroup
            row?.getChildAt(row.childCount - 1)?.let {
                onTargetReady(it)
                if (it.requestFocus() || it.requestFocusFromTouch()) {
                    it.refreshDrawableState()
                    it.jumpDrawablesToCurrentState()
                    it.invalidate()
                }
            }
        }
    }

    private fun label(value: String, size: Int, color: Int) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
