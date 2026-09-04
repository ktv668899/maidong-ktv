package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.text.TextUtils
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

class QuickSearchAdapter(
    private val context: Context,
    private val singers: List<Array<String?>>,
    private val songs: List<Song>,
    private val onSingerClick: (Array<String?>) -> Unit,
    private val onSongClick: (Song) -> Unit,
    private val onSongMore: (Song) -> Unit,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density
    private val songRows = ceil(songs.size / 2.0).toInt()

    override fun getCount(): Int = (if (singers.isEmpty()) 0 else 1) + songRows
    override fun getItem(position: Int): Any = position
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        if (singers.isNotEmpty() && position == 0) return createSingerRow()
        val songRow = position - if (singers.isEmpty()) 0 else 1
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(2) { column ->
                val song = songs.getOrNull(songRow * 2 + column)
                addView(
                    song?.let(::createSongCell) ?: View(context),
                    LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                        setMargins(dp(3), dp(2), dp(3), dp(2))
                    },
                )
            }
        }
    }

    private fun createSingerRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        singers.take(4).forEach { singer ->
            addView(createSingerTile(singer), LinearLayout.LayoutParams(0, dp(132), 1f))
        }
        repeat((4 - singers.size.coerceAtMost(4)).coerceAtLeast(0)) {
            addView(View(context), LinearLayout.LayoutParams(0, dp(132), 1f))
        }
    }

    private fun createSingerTile(singer: Array<String?>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        val name = singer.getOrNull(1).orEmpty()
        setAccessibleFocus("focus:singer:${singer.getOrNull(0) ?: name}", "歌星，$name")
        addView(ImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            SingerAvatarLoader.load(this, singer.getOrNull(5), R.drawable.ic_singer_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(5) })
        addView(TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        installPress(this)
        setOnClickListener { onSingerClick(singer) }
        TvFocusStyler.install(this)
    }

    private fun createSongCell(song: Song): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(3), dp(2), dp(3))
        setBackgroundResource(R.drawable.ott_bg_item)
        isClickable = true
        isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val marker = "focus:song:${song.id ?: song.dbId ?: song.filename ?: "${song.title}|${song.singer}"}"
        setAccessibleFocus(marker, "歌曲，${song.title.orEmpty()}，歌手，${song.singer.orEmpty()}，双击点歌，长按查看更多")
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = song.title
                textSize = 16f
                setTextColor(Color.WHITE)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(TextView(context).apply {
                text = song.singer
                textSize = 12f
                setTextColor(Color.rgb(193, 193, 193))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ott_ic_more)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = false
            isFocusable = false
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        installPress(this)
        setOnClickListener { onSongClick(song) }
        setOnLongClickListener { onSongMore(song); true }
        TvFocusStyler.install(this)
    }

    private fun installPress(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> target.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.86f).setDuration(70L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
