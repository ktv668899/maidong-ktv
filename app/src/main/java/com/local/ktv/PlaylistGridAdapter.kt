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
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.ceil

class PlaylistGridAdapter(
    private val context: Context,
    private val playlists: List<Array<String?>>,
    /** 卡片默认封面: 关键词虚拟歌单(如"广场舞")可传入主题图替代默认图标。 */
    private val coverImageRes: Int = R.drawable.ic_default_playlist_avatar,
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
            if (coverImageRes == R.drawable.ic_default_playlist_avatar) {
                // 关键词主题图标: 不同分类一眼可辨, 未命中关键词仍回退默认封面。
                setImageDrawable(AppCompatResources.getDrawable(context, iconRes(name)))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            } else {
                setImageResource(coverImageRes)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
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

    /**
     * 按歌单/分类名挑主题图标: 关键词命中即用专属矢量图, 未命中回退默认音符封面。
     * 顺序敏感: 具体词在前, 泛化词在后。
     */
    private fun iconRes(name: String): Int = when {
        name.contains("祝福") || name.contains("生日") || name.contains("喜庆") ||
            name.contains("恭喜") || name.contains("贺岁") -> R.drawable.img_cat_gift
        name.contains("友谊") || name.contains("友情") || name.contains("朋友") ||
            name.contains("同学") -> R.drawable.img_cat_hearts
        name.contains("情歌") || name.contains("爱情") || name.contains("恋") ||
            name.contains("相思") || name.contains("甜蜜") || name.contains("情人") -> R.drawable.img_cat_heart
        name.contains("经典") || name.contains("怀旧") || name.contains("老歌") ||
            name.contains("金曲") || name.contains("怀旧金曲") -> R.drawable.img_cat_vinyl
        name.contains("戏曲") || name.contains("京剧") || name.contains("越剧") ||
            name.contains("黄梅") || name.contains("豫剧") || name.contains("评剧") ||
            name.contains("剧种") || name.endsWith("剧") -> R.drawable.img_cat_opera
        name.contains("民歌") || name.contains("民族") || name.contains("山歌") ||
            name.contains("乡土") -> R.drawable.img_cat_folk
        name.contains("儿童") || name.contains("儿歌") || name.contains("卡通") ||
            name.contains("动画") || name.contains("童谣") -> R.drawable.img_cat_balloon
        name.contains("影视") || name.contains("电影") || name.contains("电视剧") ||
            name.contains("主题曲") || name.contains("插曲") -> R.drawable.img_cat_film
        name.contains("舞") || name.contains("节奏") -> R.drawable.img_cat_dance
        name.contains("校园") || name.contains("青春") || name.contains("毕业") -> R.drawable.img_cat_cap
        name.contains("军旅") || name.contains("爱国") || name.contains("红歌") ||
            name.contains("主旋律") -> R.drawable.img_cat_flag
        name.contains("思乡") || name.contains("亲情") || name.contains("家乡") ||
            name.contains("妈妈") || name.contains("父亲") || name.contains("家庭") -> R.drawable.img_cat_home
        name.contains("网络") || name.contains("外语") || name.contains("欧美") ||
            name.contains("日韩") || name.contains("全球") -> R.drawable.img_cat_globe
        name.contains("演唱") || name.contains("卡拉") || name.contains("翻唱") -> R.drawable.img_cat_mic
        name.contains("摇滚") || name.contains("电音") || name.contains("DJ") ||
            name.contains("劲爆") || name.contains("爵士") || name.contains("蓝调") -> R.drawable.img_cat_bolt
        else -> R.drawable.ic_default_playlist_avatar
    }

    companion object {
        private const val COLUMN_COUNT = 4
    }
}
