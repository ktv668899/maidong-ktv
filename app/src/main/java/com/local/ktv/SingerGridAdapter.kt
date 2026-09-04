package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/** Four-column singer grid matching the original OTT singer page. */
class SingerGridAdapter(
    private val context: Context,
    private val singers: List<Array<String?>>,
    private val onSingerClick: (Array<String?>) -> Unit,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density

    override fun getCount(): Int = ceil(singers.size / COLUMN_COUNT.toDouble()).toInt()
    override fun getItem(position: Int): Any = position
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(145))
        }
        row.removeAllViews()
        repeat(COLUMN_COUNT) { column ->
            val index = position * COLUMN_COUNT + column
            val singer = singers.getOrNull(index)
            row.addView(
                singer?.let(::createSingerTile) ?: View(context),
                LinearLayout.LayoutParams(0, dp(145), 1f),
            )
        }
        return row
    }

    private fun createSingerTile(singer: Array<String?>): View {
        val name = singer.getOrNull(1).orEmpty()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setAccessibleFocus("focus:singer:${singer.getOrNull(0) ?: name}", "歌星，$name")

            val avatar = ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.CENTER_CROP
                SingerAvatarLoader.load(this, singer.getOrNull(5), R.drawable.ic_singer_placeholder)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
            }
            addView(avatar, LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(21) })

            addView(TextView(context).apply {
                text = name
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply { topMargin = dp(4) })

            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }
                false
            }
            setOnClickListener { onSingerClick(singer) }
            TvFocusStyler.install(this)
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    companion object {
        private const val COLUMN_COUNT = 4
    }
}
