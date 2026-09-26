package com.local.ktv

import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections

/**
 * 歌曲取址的**原生兜底实现**。
 *
 * ## 为什么需要它
 * 取址原本完全依赖隐藏 WebView 里的 `ktv_api.js`。但在一部分老电视盒子/投影上
 * （实测：极米 H1S / GMUI2.0 / Android 5.1.1 / API 22）**WebView 不可用**：
 * `KtvJsBridge` 永远 `isReady()==false`，`SongApiClient` 等 15 秒后只能回退到数据库里的
 * `cloud_url`，取不到就报「无法生成下载地址」。特征是**远程日志里连一条 `JS ...` 都没有**
 * （JS 从没跑起来）。
 *
 * ## 做法
 * 用 Kotlin 复刻 `ktv_api.js` 的同一套协议：
 * `GET <host>/i.php`（取 token）→ `GET <host>/music/do.php`（取播放地址），
 * 并镜像 JS 的多源排序（失败源沉底）与广告/错号资源校验（`isDemoUrl`）。
 *
 * ⚠️ 它**必须走与 JS 相同的 HTTP 通道**（[KtvJsBridge.rawCleanGet] 的裸 Socket，
 * 只发 Host + Accept），否则会被源站风控拒绝（带 UA/Connection 头会被判"异常行为"）。
 *
 * ⚠️ 这里的 APP_ID/APP_KEY/SDK_KEY/HOSTS 是 `ktv_api.js` CONFIG 的镜像。
 * 源站配置若变更，JS 可热更新而这里需要发版 —— 因此它**只作为兜底**：
 * WebView 可用时仍以 JS 为准（见 [SongApiClient.getSongDownloadUrl]）。
 */
object SongUrlResolver {
    private const val TAG = "SongUrlResolver"

    // ── 以下与 assets/mobile/ktv_api.js 的 CONFIG 保持一致 ──
    // 注意 VER/VN 与 KtvJsBridge 注入 JS 时覆盖的值一致（不是 JS 文件里的原始值）。
    private const val APP_ID = "d4eeacc6cec3434fbc8c41608a3056a0"
    private const val APP_KEY = "024210cba40d4385a93e6c2d3249bfb5"
    private const val SDK_KEY = "19042303a8374f67ae3fe1e25c97936f"
    private const val VER = "2.0"
    private const val VN = "4.1.3.03281430"
    private const val TIMEOUT_MS = 8_000

    private val DEFAULT_HOSTS = listOf(
        "http://w.w345.my",
        "http://desktok.top",
        "http://mm.kk456.top",
        "http://w.w345.my:66",
        "http://z.w345.my",
        "http://b.sktv.top",
        "http://w345.my",
    )

    /** 运行时源优先级（与 JS 一样：解析失败的源沉到队尾）。 */
    private val hostOrder: MutableList<String> = Collections.synchronizedList(DEFAULT_HOSTS.toMutableList())

    @Volatile private var fetch: ((String) -> String?)? = null

    @Volatile private var sn: String = KtvJsBridge.DEVICE_SN

    /**
     * 最近一次取址命中的源 host。
     * 供 `SongApiClient.lastSongSourceHost()` 使用 —— 走原生兜底时 JS 的
     * `reportSongResult` 不会触发，否则日志里「请求源=」永远是空的。
     */
    @Volatile
    var lastHost: String = ""
        private set

    /**
     * 安装取址通道。
     * @param fetch 干净的 HTTP GET（应传 [KtvJsBridge.rawCleanGet]）；
     *              入参 url，返回 `{"status":..,"body":..,"error":..}` 的 JSON 字符串。
     */
    fun install(fetch: (String) -> String?) {
        this.fetch = fetch
        Log.i(TAG, "native song url resolver installed (${DEFAULT_HOSTS.size} hosts)")
    }

    private fun demote(host: String) {
        synchronized(hostOrder) {
            val index = hostOrder.indexOf(host)
            if (index >= 0 && index < hostOrder.size - 1) {
                hostOrder.removeAt(index)
                hostOrder.add(host)
            }
        }
    }

    private fun promote(host: String) {
        synchronized(hostOrder) {
            val index = hostOrder.indexOf(host)
            if (index > 0) {
                hostOrder.removeAt(index)
                hostOrder.add(0, host)
            }
        }
    }

    /** 依次尝试各源，返回可用的播放地址；全部失败返回 null。 */
    fun resolve(musicno: String, resolution: String = "720", h265: Boolean = false): String? {
        if (musicno.isBlank()) return null
        val channel = fetch ?: run {
            MiliLog.warn("NATIVE-SONG 未安装取址通道，跳过原生兜底")
            return null
        }
        val mac = runCatching { DeviceMac.effective() }.getOrDefault(sn)
        val startedAt = System.currentTimeMillis()
        val hosts = synchronized(hostOrder) { hostOrder.toList() }
        var lastDetail = "no-source"

        for ((index, host) in hosts.withIndex()) {
            val token = runCatching { fetchToken(channel, host, mac) }.getOrElse {
                MiliLog.warn("NATIVE-SONG i.php 异常 源=$host ${it.javaClass.simpleName}: ${it.message}")
                null
            }
            if (token.isNullOrEmpty()) {
                lastDetail = "token-fail"
                demote(host)
                continue
            }
            val url = runCatching { fetchSongUrl(channel, host, mac, musicno, token, resolution, h265) }
                .getOrElse {
                    MiliLog.warn("NATIVE-SONG do.php 异常 源=$host ${it.javaClass.simpleName}: ${it.message}")
                    null
                }
            if (!url.isNullOrEmpty() && isValidSongUrl(url, musicno)) {
                promote(host)
                lastHost = host
                val elapsed = System.currentTimeMillis() - startedAt
                val urlHost = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
                MiliLog.info(
                    "NATIVE-SONG 取址成功 编号=$musicno 源=$host urlHost=$urlHost " +
                        "第${index + 1}个源 用时=${elapsed}ms",
                )
                return url
            }
            lastDetail = when {
                url.isNullOrEmpty() -> "no-url"
                else -> "demo-or-mismatch"
            }
            demote(host)
        }

        val elapsed = System.currentTimeMillis() - startedAt
        MiliLog.error("NATIVE-SONG 全部源失败 编号=$musicno 详情=$lastDetail 用时=${elapsed}ms")
        return null
    }

    // ─── 协议实现 ───────────────────────────────────────

    private fun fetchToken(channel: (String) -> String?, host: String, mac: String): String? {
        val ts = System.currentTimeMillis() / 1000
        val params = "appid=$APP_ID&mac=${mac}_$sn&sn=$sn&time=$ts&ver=$VER&vn=$VN"
        val sign = md5(params + APP_KEY)
        val json = getJson(channel, "$host/i.php?$params&sign=$sign") ?: return null
        if (json.optInt("code") != 200) {
            MiliLog.warn("NATIVE-SONG token 失败 源=$host code=${json.optInt("code")} msg=${json.optString("msg")}")
            return null
        }
        return json.optString("token").takeIf { it.isNotEmpty() }
    }

    private fun fetchSongUrl(
        channel: (String) -> String?,
        host: String,
        mac: String,
        musicno: String,
        token: String,
        resolution: String,
        h265: Boolean,
    ): String? {
        val ts = System.currentTimeMillis() / 1000
        // 参数顺序、原始 token 中的 "=" 以及签名拼接必须与原 APK 一致（同 ktv_api.js）。
        val params = "appid=$APP_ID&device=${mac}_$sn&ish265=${if (h265) "1" else "0"}&ls=1" +
            "&musicno=$musicno&resolution=$resolution&sn=$sn&time=$ts&token=$token"
        val sign = md5(params + SDK_KEY)
        val json = getJson(channel, "$host/music/do.php?$params&sign=$sign") ?: return null
        val code = json.optInt("code")
        if (code != 200) {
            MiliLog.warn("NATIVE-SONG do.php 源=$host code=$code msg=${json.optString("msg")}")
            return null
        }
        return json.optString("data").takeIf { it.isNotEmpty() }
    }

    /** 解析 request() 的信封 `{status, body, error}`，再把 body 解析为 JSON。 */
    private fun getJson(channel: (String) -> String?, url: String): JSONObject? {
        val raw = channel(url) ?: return null
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val status = envelope.optInt("status")
        if (status !in 200..299) {
            MiliLog.warn(
                "NATIVE-SONG HTTP $status ${runCatching { java.net.URI(url).host }.getOrNull()} " +
                    "err=${envelope.optString("error").take(80)}",
            )
            return null
        }
        val body = envelope.optString("body")
        if (body.isBlank()) return null
        return runCatching { JSONObject(body) }.getOrElse {
            MiliLog.warn("NATIVE-SONG 响应非 JSON: ${body.take(120)}")
            null
        }
    }

    /** 镜像 ktv_api.js 的 `isDemoUrl`：广告视频 / 纯 IP / R2 测试桶 / ts 编号与请求不一致。 */
    private fun isValidSongUrl(url: String, musicno: String): Boolean {
        if (AdVideoFilter.isAdVideo(url)) return false
        val lower = url.lowercase()
        if (lower.contains("wb66") || lower.contains("demo")) return false
        if (IP_URL.containsMatchIn(url)) return false
        if (R2_DEMO.containsMatchIn(url)) return false
        val matched = TS_NAME.find(url)?.groupValues?.getOrNull(1)
        if (matched != null && matched != musicno) return false
        return true
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val IP_URL = Regex("^https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?::\\d+)?/", RegexOption.IGNORE_CASE)
    private val R2_DEMO = Regex("pub-[a-z0-9]+\\.r2\\.dev/ss/", RegexOption.IGNORE_CASE)
    private val TS_NAME = Regex("/(\\d+)\\.ts(?:\\?|$)")
}
