package com.local.ktv

import android.util.Log

/**
 * 屏蔽/替换后端注入的贴片广告视频。
 *
 * 后端在下发歌曲播放地址时,可能把广告视频(例如
 * `http://gz.ac16.vip/ad_files/my_ad_video.ts`)伪装成歌曲地址返回。
 * 这里统一识别并处理:
 * - 默认「屏蔽」:广告地址被拦下后,调用方会回落到数据库中的真实歌曲地址
 *   (`SongApiClient` 的 DB fallback),从而直接播放歌曲、跳过广告。
 * - 若需要「替换」成自己的视频,把 [replacementUrl] 设置为目标地址即可。
 */
object AdVideoFilter {
    private const val TAG = "AdVideoFilter"

    /** 命中这些片段的地址视为广告视频(匹配小写)。 */
    private val AD_MARKERS = listOf(
        "/ad_files/",
        "my_ad_video",
    )

    /**
     * 替换地址。为 null 时表示「屏蔽」(返回 null,由 DB 真实歌曲地址兜底);
     * 设置后,命中广告时用该地址替换原广告。
     */
    @Volatile
    @JvmField
    var replacementUrl: String? = null

    /** 判断是否为广告视频地址。 */
    @JvmStatic
    fun isAdVideo(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val lower = url.lowercase()
        return AD_MARKERS.any { lower.contains(it) }
    }

    /**
     * 处理播放地址:广告地址返回 null(屏蔽)或 [replacementUrl](替换);
     * 非广告地址原样返回。
     */
    @JvmStatic
    fun resolve(url: String?): String? {
        if (!isAdVideo(url)) return url
        if (replacementUrl.isNullOrEmpty()) {
            Log.w(TAG, "已屏蔽广告视频: $url")
            return null
        }
        Log.i(TAG, "已替换广告视频: $url -> $replacementUrl")
        return replacementUrl
    }
}