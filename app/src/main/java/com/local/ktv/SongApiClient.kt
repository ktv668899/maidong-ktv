package com.local.ktv

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 歌曲 API 客户端 —— JS 热更新桥接
 * =================================
 * - 通过 KtvJsBridge 调用 Gitea 上的 JS 脚本
 * - API 变更时只需更新 Gitea JS 文件，App 无需发版
 * - 首次播放时实时获取 URL，不存在过期问题
 */
object SongApiClient {
    private const val TAG = "SongApiClient"

    private var bridge: KtvJsBridge? = null
    @Volatile private var ready = false

    @JvmStatic
    fun init(context: Context) {
        if (bridge != null) return
        bridge = KtvJsBridge(context)
        // 原生兜底取址通道：复用桥的「干净请求」裸 Socket（与 JS 同一条路，风控表现一致），
        // 但**不依赖 WebView 是否可用** —— 老盒子上 WebView 不可用时唯一能取到地址的路径。
        SongUrlResolver.install { url -> bridge?.rawCleanGet(url) }
        bridge?.init { ok ->
            ready = ok
            // 失败原因由 KtvJsBridge.markUnavailable() 统一上报（只记一次），此处不再重复记日志。
            Log.i(TAG, "JS Bridge init: $ok")
        }
    }

    /** 兼容旧接口 */
    @JvmStatic
    fun init(sn: String, mac: String) {
        // JS 脚本自己生成 MAC/SN
    }

    @JvmStatic
    fun getAuthToken(): String = ""

    @JvmStatic
    fun getSongDownloadUrl(musicno: String?): String? =
        musicno?.takeIf(String::isNotBlank)?.let { getSongDownloadUrl(it, "720", false) }

    @JvmStatic
    fun getSongDownloadUrl(musicno: String, resolution: String, h265: Boolean): String? {
        // 主线程同步等待 WebView 回调会死锁；当前播放/下载流程会在 IO 线程调用这里。
        // ⚠️ 主线程**绝不能**走网络路径：原生兜底最多 7 个源 × 8 秒超时，会把 UI 线程
        // 卡死到 ANR（1.4.7 曾把这里改成先 resolveNatively 再回退，属于隐患）。
        // 主线程只做本地的 DB 兜底，网络取址一律留给后台线程。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "Synchronous request on main thread; using DB fallback only")
            return fetchFromDb(musicno)
        }
        val bridge = bridge ?: return resolveNatively(musicno, resolution, h265) ?: fetchFromDb(musicno)
        // 已判定本机 WebView 不可用 → 不再每次白等 15 秒，直接用原生通道。
        // （极米 H1S 实测：每次都等满 15 秒，远程日志里每首歌前都有一条「15s 未就绪」。）
        if (bridge.isUnavailable()) {
            return resolveNatively(musicno, resolution, h265) ?: fetchFromDb(musicno)
        }
        // 等待 JS 桥"当前"真正就绪。首次冷启动时隐藏 WebView 的初始化在低性能设备
        // (极米投影等)上可能超过 3 秒；桥热更新重载期间 isReady() 也会短暂为 false。
        // 这里放宽到 15 秒，避免第一首歌因撞上就绪窗口而回退到不可靠的 DB cloud_url。
        val deadline = System.currentTimeMillis() + 15_000L
        while (!bridge.isReady() && System.currentTimeMillis() < deadline && !bridge.isDestroyed()) {
            try { Thread.sleep(100) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!bridge.isReady()) {
            // ⚠️ 老盒子（WebView 不可用）会一直走到这里。以前直接回退 DB，结果就是
            // 「无法生成下载地址」且远程日志里连一条 JS 日志都没有。现在改走原生解析器，
            // 并把"不可用"记成持久状态（只上报一次），后续点歌不再等这 15 秒。
            Log.w(TAG, "Bridge not ready after wait, native resolver")
            bridge.markUnavailable("15s 未就绪")
            return resolveNatively(musicno, resolution, h265) ?: fetchFromDb(musicno)
        }
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        bridge.getSongUrl(musicno, resolution, h265) { url -> result.set(url); latch.countDown() }
        // JS 需要覆盖主/备节点和最多 3 次设备重试；略长于桥内 180 秒总超时。
        try {
            latch.await(185, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 桥可用但 JS 没取到地址（或超时）时，同样再给原生解析器一次机会，
        // 避免 JS 侧偶发失败直接把第一首歌判死。
        val url = result.get() ?: resolveNatively(musicno, resolution, h265) ?: fetchFromDb(musicno)
        if (url != null) {
            // 成功取到歌曲 URL → 歌曲源窗口已恢复/未到期, 复位到期提示状态。
            Eac19WindowGuard.noteUrlOk()
        }
        return url
    }

    /**
     * 原生兜底取址：WebView 不可用（老盒子）或 JS 没取到地址时使用。
     * 走的是与 JS 完全相同的裸 Socket 干净请求通道，见 [SongUrlResolver]。
     */
    private fun resolveNatively(musicno: String, resolution: String, h265: Boolean): String? =
        runCatching { SongUrlResolver.resolve(musicno, resolution, h265) }
            .onFailure { Log.w(TAG, "native resolver failed", it) }
            .getOrNull()

    /** DB cloud_url fallback — 必须通过 demo 检测才能使用 */
    private fun fetchFromDb(musicno: String): String? {        try {
            val db = MuseDatabase()
            if (db.open()) {
                val url = db.getCloudUrl(musicno)
                db.close()
                // DB 中的 cloud_url 也可能就是广告/demo 地址，必须过同一道可信校验
                // （2026-09-26 广告事故：这里原来只做 isLikelyDemoUrl，挡不住广告域名）。
                if (url != null && !isLikelyDemoUrl(url) && AdVideoFilter.isTrustedSongUrl(url, musicno)) {
                    return url
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** 快速判断是否为 demo URL (不含 JS 的完整逻辑, 仅做硬特征兜底) */
    private fun isLikelyDemoUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return true
        // 纯 IP 地址 → 高概率 demo
        if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) return true
        // Cloudflare R2 demo bucket (pub-*.r2.dev)
        if (host.matches(Regex("^pub-[a-z0-9]+\\.r2\\.dev$"))) return true
        return false
    }

    /**
     * 重置歌曲源会话（清 JS 侧 token / API 实例）。
     *
     * 注意：这里原来是**空实现**，导致断网重连后仍复用旧 token —— 现象是
     * 「日志提示歌曲源获取成功（地址拿到了），但实际下载失败/403」。
     */
    @JvmStatic
    fun clearTokenCache() {
        bridge?.resetApiSession()
    }

    /** 语义更明确的别名：断网恢复后重建歌曲源会话。 */
    @JvmStatic
    fun resetSourceSession() {
        bridge?.resetApiSession()
    }

    /** 最近一次取址命中的源：优先 JS 上报，其次原生兜底记录（供远程日志的「请求源=」）。 */
    @JvmStatic
    fun lastSongSourceHost(): String =
        bridge?.lastSongSourceHost?.takeIf { it.isNotEmpty() } ?: SongUrlResolver.lastHost
}
