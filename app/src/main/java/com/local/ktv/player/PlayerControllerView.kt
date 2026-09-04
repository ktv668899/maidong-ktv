package com.local.ktv.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.local.ktv.Song
import com.local.ktv.TvFocusStyler

class PlayerControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    interface OnControlListener {
        fun onPlayPause()
        fun onPrev()
        fun onNext()
        fun onVocalSwitch(original: Boolean)
        fun onVolume()
        fun onFullScreen()
    }

    private val main = Handler(Looper.getMainLooper())
    private val autoHideTask = Runnable(::hide)
    private lateinit var songTitle: TextView
    private lateinit var songSinger: TextView
    private lateinit var playPause: TextView
    private lateinit var vocalSwitch: TextView
    private var listener: OnControlListener? = null
    private var originalVocal = false
    private var controlVisible = true

    init {
        setBackgroundColor(Color.argb(120, 0, 0, 0))
        setOnClickListener { resetAutoHide() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildTopBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(buildMiddle(), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(buildBottomBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        show()
    }

    fun setOnControlListener(listener: OnControlListener?) {
        this.listener = listener
    }

    fun updateSongInfo(song: Song?) {
        songTitle.text = song?.title ?: "暂无歌曲"
        songSinger.text = song?.singer.orEmpty()
    }

    fun updatePlayState(playing: Boolean) {
        playPause.text = if (playing) "暂停" else "播放"
    }

    fun setVocalMode(original: Boolean) {
        originalVocal = original
        vocalSwitch.text = if (original) "伴唱" else "原唱"
    }

    fun show() {
        controlVisible = true
        visibility = VISIBLE
        resetAutoHide()
    }

    fun hide() {
        controlVisible = false
        visibility = GONE
        main.removeCallbacks(autoHideTask)
    }

    fun toggle() = if (controlVisible) hide() else show()

    fun isControlVisible(): Boolean = controlVisible

    fun resetAutoHide() {
        main.removeCallbacks(autoHideTask)
        main.postDelayed(autoHideTask, 5_000)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(autoHideTask)
        super.onDetachedFromWindow()
    }

    private fun buildTopBar() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(8))
        songTitle = label("暂无歌曲", GOLD, 20).also {
            it.gravity = Gravity.CENTER_VERTICAL
            addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        songSinger = label("", TEXT_DIM, 14).also {
            it.gravity = Gravity.CENTER_VERTICAL
            addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun buildMiddle() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        playPause = button("播放", TEXT_WHITE, 22) { listener?.onPlayPause() }
        addView(playPause, LinearLayout.LayoutParams(dp(96), dp(96)))
    }

    private fun buildBottomBar() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
        addControl("上一首") { listener?.onPrev() }
        vocalSwitch = button("原唱", ACCENT_RED, 16) {
            // MainActivity 确认目标模式后会通过 setVocalMode 回写，避免按钮先翻转造成提示相反。
            listener?.onVocalSwitch(!originalVocal)
        }.also { addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        addControl("音量") { listener?.onVolume() }
        addControl("全屏") { listener?.onFullScreen() }
        addControl("下一首") { listener?.onNext() }
    }

    private fun LinearLayout.addControl(text: String, action: () -> Unit) {
        addView(button(text, TEXT_WHITE, 16, action), LinearLayout.LayoutParams(0, dp(48), 1f))
    }

    private fun label(text: String, color: Int, size: Int) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = size.toFloat()
        isSingleLine = true
    }

    private fun button(text: String, color: Int, size: Int, action: () -> Unit) =
        label(text, color, size).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 22, 31, 42))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.argb(80, 241, 241, 241))
            }
            setOnClickListener {
                action()
                resetAutoHide()
            }
            TvFocusStyler.install(this)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        val GOLD = Color.rgb(245, 190, 89)
        val ACCENT_RED = Color.rgb(253, 51, 89)
        val TEXT_WHITE = Color.rgb(241, 241, 241)
        val TEXT_DIM = Color.argb(180, 241, 241, 241)
    }
}
