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
     * 广告视频专用域名(命中即判定为广告，含其子域)。
     *
     * 2026-09-26 现场事故：新设备（WebView 正常 → 走 JS 取址）点歌 7303235 拿到了
     * `gz.ac16.vip` 的广告视频，而且它**通过了内容校验**（≥6MB / ≥60s / TS 包完整），
     * 于是被当歌曲缓存并播放。仅靠路径片段（`/ad_files/`、`my_ad_video`）不足以拦住 ——
     * 广告方一改文件名就漏，因此必须**按域名**兜底。
     */
    private val AD_HOSTS = listOf(
        "gz.ac16.vip",
        "ac16.vip",
    )

    /**
     * 替换地址。为 null 时表示「屏蔽」(返回 null,由 DB 真实歌曲地址兜底);
     * 设置后,命中广告时用该地址替换原广告。
     */
    @Volatile
    @JvmField
    var replacementUrl: String? = null

    /** 判断是否为广告视频地址（路径特征 + 域名）。 */
    @JvmStatic
    fun isAdVideo(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val lower = url.lowercase()
        if (AD_MARKERS.any { lower.contains(it) }) return true
        val host = hostOf(url) ?: return false
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 取址链路的**统一可信校验**（所有来源的地址都必须过这一关）。
     *
     * 判定为不可信的情况：
     * 1. 非 http(s)；
     * 2. 命中广告特征（[isAdVideo]）；
     * 3. **URL 里的 `.ts` 文件名与点歌编号不一致** —— 真源 URL 的文件名恒等于点歌编号
     *    （见 `ktv_api.js` 的说明），而广告文件常叫 `my_ad_video.ts`。
     *    旧规则只处理"纯数字且与编号不同"（`/\/(\d+)\.ts/`），非数字文件名直接放行，
     *    这正是本次广告漏放的直接原因。
     */
    @JvmStatic
    fun isTrustedSongUrl(url: String?, musicno: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (isAdVideo(url)) return false
        val name = TS_BASENAME.find(url)?.groupValues?.getOrNull(1)
        if (name != null && !musicno.isNullOrEmpty() && name != musicno) return false
        return true
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

    /** 取 `.ts` 的**文件名主体**（不含扩展名），供与点歌编号比对。 */
    private val TS_BASENAME = Regex("/([^/?]+)\\.ts(?:\\?|$)", RegexOption.IGNORE_CASE)

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