package com.local.ktv

import android.util.Log

/**
 * 歌曲源(w.w345.my)"试用窗口"守卫（最小版）。
 *
 * 抓包/实测结论: 歌曲源对陌生出口 IP 发放约 10 分钟试用窗口
 * (api.php: trial_times:2 × trial_duration:5), do.php 在该窗口外返回
 * 403"检测到异常行为", 换公网 IP 后立即恢复。额度记在出口 IP 上,
 * 与设备无关(同 IP 换 device 无效)。
 *
 * 职责:
 *  - 收到 do.php 风控 403 信号时通知 UI 弹一次提示(引导重启光猫/路由器换 IP);
 *  - 取 URL 成功(窗口恢复)后自动复位, 下一次到期可再次提示;
 *  - 全程不阻塞、不影响播放(本地已缓存歌曲照常播)。
 */
object Eac19WindowGuard {
    private const val TAG = "Eac19WindowGuard"

    /** UI 层订阅: 窗口到期(风控 403)时回调(已在主线程)。 */
    @Volatile var onBlocked: (() -> Unit)? = null

    /** 距离上次提示的最小间隔, 防止连续失败时反复弹窗。 */
    private const val MIN_PROMPT_INTERVAL_MS = 15 * 60_000L

    private val lock = Any()
    private var lastPromptAt = 0L
    private var blockedEpisodeActive = false

    /** 由 JS 桥在 do.php 返回风控 403 时调用(任意线程)。 */
    fun noteRiskBlocked() {
        val now = System.currentTimeMillis()
        val shouldNotify: Boolean
        synchronized(lock) {
            if (!blockedEpisodeActive) {
                blockedEpisodeActive = true
                lastPromptAt = now
                shouldNotify = true
            } else {
                shouldNotify = now - lastPromptAt >= MIN_PROMPT_INTERVAL_MS
                if (shouldNotify) lastPromptAt = now
            }
        }
        if (shouldNotify) {
            Log.w(TAG, "歌曲源 risk-control 403 (trial window expired)")
            onBlocked?.invoke()
        }
    }

    /** 取到有效歌曲 URL(窗口恢复)时调用, 复位本次到期 episode。 */
    fun noteUrlOk() {
        synchronized(lock) {
            blockedEpisodeActive = false
        }
    }
}
