package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

class PlaylistGridAdapter(
    private val context: Context,
    private val playlists: List<Array<String?>>,
    private val onPlaylistClick: (Array<String?>) -> Unit,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density

    override fun getCount(): Int = ceil(playlists.size / COLUMN_COUNT.toDouble()).toInt()
    override fun getItem(position: Int): Any = position
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.removeAllViews()
        repeat(COLUMN_COUNT) { column ->
            val playlist = playlists.getOrNull(position * COLUMN_COUNT + column)
            row.addView(
                playlist?.let(::createTile) ?: View(context),
                LinearLayout.LayoutParams(0, dp(116), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                },
            )
        }
        return row
    }

    private fun createTile(playlist: Array<String?>): View = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.ott_bg_rank_item_not_selected)
        isClickable = true
        isFocusable = true
        val name = playlist.getOrNull(1).orEmpty()
        setAccessibleFocus(
            "focus:playlist:${playlist.getOrNull(0) ?: name}",
            "歌单，$name",
        )

        addView(ImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(R.drawable.ic_default_playlist_avatar)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0.9f
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82), Gravity.TOP))

        addView(TextView(context).apply {
            text = playlist.getOrNull(1).orEmpty()
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            setBackgroundResource(R.drawable.ott_bg_playlist_name)
            setPadding(dp(4), 0, dp(4), 0)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34), Gravity.BOTTOM))

        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.86f).setDuration(70L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
        setOnClickListener { onPlaylistClick(playlist) }
        TvFocusStyler.install(this)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    companion object {
        private const val COLUMN_COUNT = 4
    }
}
