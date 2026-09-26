package com.local.ktv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 广告地址拦截的回归测试。
 *
 * 对应 2026-09-26 现场事故：新设备（WebView 正常 → 走 JS 取址）点歌 7303235（情歌 HD）
 * 拿到 `gz.ac16.vip` 的广告视频，被当成歌曲下载并播放。当时的漏洞有三处：
 * 1. JS 的 isDemoUrl 不认识该广告域名，且只比对"纯数字文件名"，非数字名字直接放行；
 * 2. Kotlin 的 buildDownloadUrl 成功分支**完全没做广告校验**；
 * 3. DB 回退路径只做 isLikelyDemoUrl，也挡不住广告域名。
 * 这里用日志里的**真实 URL 形态**锁住入参校验行为。
 */
public final class AdVideoFilterTest {

    /** 真源地址：两个 CDN 域实测文件名都严格等于点歌编号，必须放行。 */
    @Test
    public void acceptsRealSourceUrls() {
        assertTrue(AdVideoFilter.isTrustedSongUrl(
                "http://txog.ktvsky.com/264/lvbr/480/E/ts/47.1/202209220333/480p/7303235.ts?token=526e505e",
                "7303235"));
        assertTrue(AdVideoFilter.isTrustedSongUrl(
                "http://download.origjoy.com/264/lvbr/480/E/ts/39.6/202309070206/480p/7445418.ts?token=abc",
                "7445418"));
    }

    /** 广告域名：即使文件名伪装成正确的点歌编号，也必须拦下。 */
    @Test
    public void rejectsAdHostEvenWithMatchingName() {
        assertFalse(AdVideoFilter.isTrustedSongUrl("http://gz.ac16.vip/ad/7303235.ts", "7303235"));
        assertFalse(AdVideoFilter.isTrustedSongUrl("http://cdn.ac16.vip/x/7303235.ts", "7303235"));
    }

    /** 广告文件名的经典形态：非数字名字，旧规则的正则匹配不到。 */
    @Test
    public void rejectsAdFileName() {
        assertFalse(AdVideoFilter.isTrustedSongUrl("http://example.com/ad_files/my_ad_video.ts", "7303235"));
        assertFalse(AdVideoFilter.isTrustedSongUrl("http://example.com/x/my_ad_video.ts", "7303235"));
    }

    /** .ts 文件名与点歌编号不符（即使不命中任何广告域名）也视为不可信。 */
    @Test
    public void rejectsMismatchedFileName() {
        assertFalse(AdVideoFilter.isTrustedSongUrl("http://example.com/x/9999999.ts", "7303235"));
        assertFalse(AdVideoFilter.isTrustedSongUrl("http://example.com/x/promo.ts", "7303235"));
    }

    /** 非 http(s) 一律不可信。 */
    @Test
    public void rejectsNonHttpSchemes() {
        assertFalse(AdVideoFilter.isTrustedSongUrl("file:///sdcard/x/7303235.ts", "7303235"));
        assertFalse(AdVideoFilter.isTrustedSongUrl("ftp://example.com/7303235.ts", "7303235"));
        assertFalse(AdVideoFilter.isTrustedSongUrl("", "7303235"));
        assertFalse(AdVideoFilter.isTrustedSongUrl(null, "7303235"));
    }

    /** 没有 .ts 文件名时不做文件名比对（避免误杀非 ts 的真源）。 */
    @Test
    public void allowsNonTsUrlWithoutAdMarkers() {
        assertTrue(AdVideoFilter.isTrustedSongUrl("http://cdn.example.com/path/stream?token=abc", "7303235"));
    }
}
