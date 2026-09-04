package com.local.ktv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.ceil

data class SongRowState(
    val current: Boolean = false,
    val queued: Boolean = false,
    val local: Boolean = false,
    val downloading: Boolean = false,
    val failed: Boolean = false,
    val paused: Boolean = false,
    val progress: Int = 0,
)

class SongListCallbacks(
    val onPrimary: (Song) -> Unit,
    val onDownload: (Song) -> Unit,
    val onMore: (Song) -> Unit,
    val onTop: (Song) -> Unit,
    val onDelete: (Song) -> Unit,
    val onPauseResume: (Song) -> Unit,
)

/** Stateful row renderer based on the original OTT song/rank/order/download layouts. */
class SongListAdapter(
    private val context: Context,
    private val songs: List<Song>,
    private val mode: String,
    private val pageOffset: Int,
    private val previousPageFocusId: Int,
    private val nextPageFocusId: Int,
    private val stateFor: (Song) -> SongRowState,
    private val callbacks: SongListCallbacks,
) : BaseAdapter() {
    private val density = context.resources.displayMetrics.density
    private val columns = if (mode in SINGLE_COLUMN_MODES) 1 else 2
    private val cardIds = HashMap<String, Int>()

    private fun cardId(index: Int): Int = cardIds.getOrPut(songKey(songs[index])) { View.generateViewId() }

    override fun getCount(): Int = ceil(songs.size / columns.toDouble()).toInt()
    override fun getItem(position: Int): Any = position
    override fun getItemId(position: Int): Long = songKey(songs[position * columns]).hashCode().toLong()
    override fun hasStableIds(): Boolean = true

    fun matches(renderMode: String, renderPageOffset: Int): Boolean =
        mode == renderMode && pageOffset == renderPageOffset

    fun adapterPositionFor(focusMarker: String): Int? {
        val key = focusMarker.removePrefix(FOCUS_PREFIX)
        val index = songs.indexOfFirst { songKey(it) == key }
        return index.takeIf { it >= 0 }?.div(columns)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        if (columns == 1) {
            return createSingleRow(songs[position], position).apply {
                if (position == count - 1) nextFocusDownId = previousPageFocusId
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val cells = mutableListOf<View>()
            repeat(2) { column ->
                val index = position * 2 + column
                val cell = songs.getOrNull(index)?.let {
                    createCatalogCell(it, index, column, position == count - 1)
                } ?: View(context)
                cells += cell
                addView(
                    cell,
                    LinearLayout.LayoutParams(0, dp(60), 1f),
                )
            }
        }
    }

    private fun createSingleRow(song: Song, index: Int): View = when (mode) {
        "rank" -> createRankRow(song, index)
        "ordered" -> createOrderedRow(song, index)
        "downloads" -> createDownloadRow(song, index)
        "sang" -> createSangRow(song, index)
        else -> createCatalogCell(song, index, 0, index == count - 1)
    }

    private fun createCatalogCell(song: Song, songIndex: Int, column: Int, lastRow: Boolean): View {
        val state = stateFor(song)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), 0, dp(4))
            setBackgroundResource(R.drawable.ott_bg_item)
            val info = songInfo(song, stacked = true)
            addView(info, LinearLayout.LayoutParams(0, dp(52), 1f))
            if (!state.local || state.queued) {
                addView(icon(if (state.queued) R.drawable.ic_order_song else R.drawable.ott_ic_download, null), LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            addView(icon(R.drawable.ott_ic_more, null), LinearLayout.LayoutParams(dp(40), dp(40)))
            configureSongCard(this, song, songIndex)
            if (songIndex >= columns) nextFocusUpId = cardId(songIndex - columns)
            if (column == 1) nextFocusLeftId = cardId(songIndex - 1)
            if (column == 0 && songIndex + 1 < songs.size) nextFocusRightId = cardId(songIndex + 1)
            if (!lastRow && songIndex + columns < songs.size) nextFocusDownId = cardId(songIndex + columns)
            if (lastRow) {
                nextFocusDownId = if (column == 0) previousPageFocusId else nextPageFocusId
            }
        }
    }

    private fun createRankRow(song: Song, index: Int): View {
        val state = stateFor(song)
        val order = pageOffset + index + 1
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.ott_bg_item)
            val badge = if (order <= 3) {
                icon(
                    intArrayOf(R.drawable.ic_rank_order_1, R.drawable.ic_rank_order_2, R.drawable.ic_rank_order_3)[order - 1],
                    null,
                )
            } else {
                text(order.toString(), 16, Color.WHITE, Gravity.CENTER)
            }
            addView(badge, LinearLayout.LayoutParams(dp(47), dp(48)))
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(48), 1f))
            if (!state.local || state.queued) {
                addView(icon(if (state.queued) R.drawable.ic_order_song else R.drawable.ott_ic_download, null), LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            addView(icon(R.drawable.ott_ic_more, null), LinearLayout.LayoutParams(dp(40), dp(40)))
            configureSongCard(this, song, index)
        }
    }

    private fun createOrderedRow(song: Song, index: Int): View {
        val state = stateFor(song)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.ott_bg_download)
            addView(
                if (state.current) icon(R.drawable.ott_ic_playing, null)
                else text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER),
                LinearLayout.LayoutParams(dp(47), dp(48)),
            )
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(48), 1f))
            if (!state.current) {
                addView(icon(R.drawable.ic_add_to_top) { callbacks.onTop(song) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(icon(R.drawable.ic_delete) { callbacks.onDelete(song) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            }
            configureSongCard(this, song, index)
        }
    }

    private fun createDownloadRow(song: Song, index: Int): View {
        val state = stateFor(song)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.ott_bg_download)
            addView(text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER), LinearLayout.LayoutParams(dp(47), dp(48)))
            addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(48), 1f))
            if (state.downloading) {
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = state.progress
                    progressDrawable = context.getDrawable(R.drawable.bg_download_progress)
                }, LinearLayout.LayoutParams(dp(90), dp(8)).apply { marginEnd = dp(6) })
            }
            when {
                state.failed || state.paused -> addView(
                    icon(R.drawable.ic_download_retry) { callbacks.onPauseResume(song) },
                    LinearLayout.LayoutParams(dp(36), dp(36)),
                )
                state.downloading -> addView(
                    icon(R.drawable.ic_pause) { callbacks.onPauseResume(song) },
                    LinearLayout.LayoutParams(dp(36), dp(36)),
                )
                state.local -> addView(
                    icon(R.drawable.ott_ic_play) { callbacks.onPrimary(song) },
                    LinearLayout.LayoutParams(dp(36), dp(36)),
                )
            }
            addView(icon(R.drawable.ic_delete) { callbacks.onDelete(song) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            configureSongCard(this, song, index)
        }
    }

    private fun createSangRow(song: Song, index: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.ott_bg_item)
        addView(text((index + 1).toString(), 16, Color.WHITE, Gravity.CENTER), LinearLayout.LayoutParams(dp(47), dp(48)))
        addView(songInfo(song, stacked = false), LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(icon(R.drawable.ic_order_song) { callbacks.onPrimary(song) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        configureSongCard(this, song, index)
    }

    private fun songInfo(song: Song, stacked: Boolean): View = LinearLayout(context).apply {
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (stacked) {
            addView(text(song.title.orEmpty(), 16, Color.WHITE, Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(text(song.singer.orEmpty(), 14, Color.rgb(193, 193, 193), Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            addView(text(song.title.orEmpty(), 16, Color.WHITE, Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) })
            addView(text(song.singer.orEmpty(), 16, Color.rgb(193, 193, 193), Gravity.START or Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun text(value: String, size: Int, color: Int, gravityValue: Int) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = gravityValue
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun icon(resource: Int, action: (() -> Unit)?): ImageView = ImageView(context).apply {
        setImageResource(resource)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(7), dp(7), dp(7), dp(7))
        isFocusable = action != null
        isClickable = action != null
        action?.let { callback ->
            id = View.generateViewId()
            installPressAnimation(this)
            setOnClickListener { callback() }
        }
    }

    private fun configureSongCard(card: View, song: Song, songIndex: Int) {
        card.id = cardId(songIndex)
        val state = stateFor(song)
        val stateText = when {
            state.current -> "正在播放"
            state.queued -> "已点"
            state.downloading -> "正在下载百分之${state.progress}"
            state.local -> "已下载"
            else -> "未下载"
        }
        card.setAccessibleFocus(
            FOCUS_PREFIX + songKey(song),
            "歌曲，${song.title.orEmpty()}，歌手，${song.singer.orEmpty()}，$stateText，双击点歌，长按查看更多",
        )
        (card as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        installPressAnimation(card)
        card.setOnClickListener { callbacks.onPrimary(song) }
        card.setOnLongClickListener { callbacks.onMore(song); true }
    }

    private fun installPressAnimation(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        TvFocusStyler.install(view)
        view.setOnTouchListener { target, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> target.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.82f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
            }
            false
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    companion object {
        const val FOCUS_PREFIX = "focus:song:"
        private val SINGLE_COLUMN_MODES = setOf("rank", "ordered", "downloads", "sang")

        private fun songKey(song: Song): String =
            (song.id ?: song.dbId ?: song.filename ?: "${song.title}|${song.singer}").toString()
    }
}
