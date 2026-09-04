package com.local.ktv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

/**
 * 在隐藏 WebView 中运行可热更新的歌曲接口脚本。
 *
 * JS 决定接口地址、参数、签名和响应解析；Android 只提供 HTTP 与 RSA 这两个
 * WebView 中不可靠的底层能力。这样接口变化时只需更新 Gitee 上的 ktv_api.js。
 */
class KtvJsBridge(context: Context) {
    companion object {
        private const val TAG = "KtvJsBridge"
        private const val JS_FILE = "mobile/ktv_api.js"
        private const val CACHE_FILE = "ktv_api_cache.js"
        private const val REMOTE_JS_URL =
            "https://gitee.com/zijunchiang/maidong-ktv/raw/master/app/src/main/assets/mobile/ktv_api.js"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val JS_REQUEST_TIMEOUT_MS = 180_000
        // 当前歌曲源 w.w345.my 使用的设备标识(mac + sn)。与用户抓包示例一致,
        // w.w345.my 与 e.ac19.cn 同后台,按 device 识别; 随机/变化的 device 会被风控。
        private const val DEVICE_MAC = "ae60e9547958"
        private const val DEVICE_SN = "2d7bb4a7d7f7"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, (String?) -> Unit>()

    @Volatile private var webView: WebView? = null
    @Volatile private var ready = false
    @Volatile private var destroyed = false
    private var loadedFromCache = false
    private var assetRetryAttempted = false

    /** 最近一次点歌实际命中的歌曲源 host(由 JS reportSongResult 更新, 供远程日志)。 */
    @Volatile
    var lastSongSourceHost: String = ""
        private set

    /** 必须最终在主线程创建 WebView；可从任意线程调用。 */
    fun init(callback: (Boolean) -> Unit) {
        mainHandler.post {
            if (destroyed) {
                callback(false)
                return@post
            }
            try {
                val cached = File(appContext.filesDir, CACHE_FILE)
                val cachedScript = cached.takeIf(::isValidScript)?.readText(Charsets.UTF_8)
                loadedFromCache = cachedScript != null
                createWebView(cachedScript ?: readAssetScript(), callback)
                updateCache(cached)
            } catch (error: Throwable) {
                Log.e(TAG, "WebView init failed", error)
                callback(false)
            }
        }
    }

    private fun createWebView(script: String, callback: (Boolean) -> Unit) {
        webView?.destroy()
        ready = false
        webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(NativeBridge(), "android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("typeof window.KtvApi === 'function'") { result ->
                        if (result == "true") {
                            ready = true
                            Log.i(TAG, "JS runtime ready (${if (loadedFromCache) "cache" else "asset"})")
                            callback(true)
                        } else if (loadedFromCache && !assetRetryAttempted) {
                            Log.w(TAG, "Cached JS failed to load; falling back to packaged asset")
                            assetRetryAttempted = true
                            loadedFromCache = false
                            createWebView(readAssetScript(), callback)
                        } else {
                            Log.e(TAG, "KtvApi was not exported by JS")
                            callback(false)
                        }
                    }
                }
            }
            loadDataWithBaseURL("https://localhost/", wrapHtml(script), "text/html", "UTF-8", null)
        }
    }

    /** 桥当前是否可用。热更新重载期间会短暂为 false，调用方应据此等待而非立即回退。 */
    fun isReady(): Boolean = ready && !destroyed

    fun isDestroyed(): Boolean = destroyed

    fun getSongUrl(
        musicNo: String,
        resolution: String,
        h265: Boolean,
        callback: (String?) -> Unit,
    ) {
        if (!ready || destroyed) {
            callback(null)
            return
        }
        val requestId = UUID.randomUUID().toString()
        callbacks[requestId] = callback
        val script = """
            (function() {
              var requestId = ${JSONObject.quote(requestId)};
              var completed = false;
              function finish(url) {
                if (completed) return;
                completed = true;
                clearTimeout(timer);
                android.onUrl(requestId, url || '');
              }
              var timer = setTimeout(function() { finish(''); }, $JS_REQUEST_TIMEOUT_MS);
              Promise.resolve().then(async function() {
                if (!window.api) {
                  // 歌曲源按 device 识别:随机/每次更换的 device 会被判"异常行为"。
                  // 固定使用抓包注册设备的 mac+sn(与抓包请求一致),并保持版本号一致。
                  if (window.KtvApiConfig) {
                    window.KtvApiConfig.VER = '2.0';
                    window.KtvApiConfig.VN = '4.1.3.03281430';
                  }
                  window.api = new window.KtvApi({mac: ${JSONObject.quote(DEVICE_MAC)}, sn: ${JSONObject.quote(DEVICE_SN)}, maxDeviceRetries: 0, debug: true});
                  var _regen = window.api.regenerateDevice;
                  window.api.regenerateDevice = function() {
                    _regen.call(window.api);
                    window.api.mac = ${JSONObject.quote(DEVICE_MAC)};
                    window.api.sn = ${JSONObject.quote(DEVICE_SN)};
                  };
                }
                if (!window._apiInitPromise) {
                  window._apiInitPromise = window.api.init().then(function(ok) {
                    if (!ok) throw new Error('API initialization failed');
                    return true;
                  }).catch(function(error) {
                    window._apiInitPromise = null;
                    throw error;
                  });
                }
                await window._apiInitPromise;
                return window.api.getSongUrl(
                  ${JSONObject.quote(musicNo)}, ${JSONObject.quote(resolution)}, ${if (h265) "true" else "false"}
                );
              }).then(finish).catch(function(error) {
                android.log('getSongUrl failed: ' + (error && error.message ? error.message : error));
                finish('');
              });
            })();
        """.trimIndent()
        mainHandler.post {
            if (!ready) complete(requestId, null)
            else webView?.evaluateJavascript(script, null) ?: complete(requestId, null)
        }
    }

    // JS isDemoUrl 已做第一层正则过滤; 这里再做域名白名单兜底, 防止 demo URL 绕过
    private val trustedHostSuffixes = listOf(
        "ktvsky.com", "origjoy.com", "cherryonline.cn", "ac16.vip",
        "ktvdaren.com", "j-make.cn",
    )
    // 已知 demo 域名模式 (Cloudflare R2 测试桶、IP 地址等)
    private val demoHostPatterns = listOf(
        Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),  // 纯 IP
        Regex("^pub-[a-z0-9]+\\.r2\\.dev$"),                      // R2 demo bucket
    )

    private fun isTrustedDownloadUrl(url: String?): Boolean {
        if (url == null) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        if (demoHostPatterns.any { it.matches(host) }) {
            Log.w(TAG, "Rejected demo URL host: $host")
            return false
        }
        val trusted = trustedHostSuffixes.any { host.endsWith(it) }
        if (!trusted) Log.w(TAG, "Untrusted URL host: $host")
        return trusted
    }

    private fun complete(requestId: String, url: String?) {
        // 先屏蔽/替换广告视频,再做域名白名单校验。
        callbacks.remove(requestId)?.invoke(AdVideoFilter.resolve(url)?.takeIf { isTrustedDownloadUrl(it) })
    }

    private fun readAssetScript(): String =
        appContext.assets.open(JS_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun isValidScript(file: File): Boolean =
        runCatching {
            file.isFile && file.length() in 1_001..500_000 &&
                file.readText(Charsets.UTF_8).let {
                    it.contains("KtvApi") && it.contains("KTV_BRIDGE_API: 8")
                }
        }.getOrDefault(false)

    /** 下载成功后原子替换缓存；新脚本在下次启动时生效。 */
    private fun updateCache(cacheFile: File) {
        Thread({
            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            try {
                val connection = (URL(REMOTE_JS_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "KTV-Updater/2.0")
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("HTTP ${connection.responseCode}")
                    }
                    val declaredLength = connection.contentLengthLong
                    require(declaredLength < 0 || declaredLength <= MAX_RESPONSE_BYTES) { "JS file is too large" }
                    val bytes = connection.inputStream.use { it.readBytes() }
                    require(bytes.size <= MAX_RESPONSE_BYTES) { "JS file is too large" }
                    val script = String(bytes, StandardCharsets.UTF_8)
                    require(
                        script.length > 1000 && script.contains("KtvApi") &&
                            script.contains("KTV_BRIDGE_API: 8"),
                    ) { "Invalid or incompatible JS file" }
                    tempFile.writeText(script, Charsets.UTF_8)
                    if (!tempFile.renameTo(cacheFile)) {
                        cacheFile.writeText(script, Charsets.UTF_8)
                        tempFile.delete()
                    }
                    Log.i(TAG, "JS cache updated from Gitee (${script.length} chars)")
                    // 启动后刚拉到的新版本直接生效；若已有请求在执行，则留到下次启动。
                    mainHandler.post {
                        if (ready && callbacks.isEmpty() && !destroyed) {
                            loadedFromCache = true
                            createWebView(script) { ok -> Log.i(TAG, "Hot-reloaded JS: $ok") }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Throwable) {
                tempFile.delete()
                Log.w(TAG, "JS update failed: ${error.message}")
            }
        }, "ktv-js-updater").start()
    }

    private fun wrapHtml(script: String): String {
        val escaped = script.replace("</script", "<\\/script", ignoreCase = true)
        return "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><script>$escaped</script></body></html>"
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun log(message: String) = Log.d(TAG, message)

        @JavascriptInterface
        fun onUrl(requestId: String, url: String) {
            Log.d(TAG, "URL result ${requestId.take(8)}: ${url.take(100)}")
            complete(requestId, url)
        }

        @JavascriptInterface
        fun rsaEncryptPkcs1(publicKeyBase64: String, plaintext: String): String =
            runCatching {
                val keyBytes = Base64.decode(publicKeyBase64.replace("\\s".toRegex(), ""), Base64.DEFAULT)
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            }.onFailure { Log.e(TAG, "RSA encryption failed", it) }.getOrDefault("")

        @JavascriptInterface
        fun httpGet(url: String, headersJson: String, timeoutMs: Int): String =
            request("GET", url, null, headersJson, timeoutMs)

        @JavascriptInterface
        fun httpPost(url: String, body: String, headersJson: String, timeoutMs: Int): String =
            request("POST", url, body, headersJson, timeoutMs)

        // 任务3: JS 点歌结果上报(成功/失败 + 实际命中的歌曲源 host)。
        // 由 Kotlin 调用方(SongOkDownloadManager)补充标题后写入远程日志,
        // 同时成功即表示窗口/网络恢复 → 复位 e.ac19 窗口守卫提示状态。
        @JavascriptInterface
        fun reportSongResult(status: String, host: String, musicno: String, detail: String) {
            val ok = status == "ok"
            if (host.isNotBlank()) lastSongSourceHost = host
            if (ok) Eac19WindowGuard.noteUrlOk()
            // 具体含标题的日志由 SongOkDownloadManager 在拿到 URL 后统一写,
            // 这里只留简短来源记录, 避免重复刷屏。
            if (!ok) {
                MiliLog.error("点歌失败 编号=$musicno 请求源=$host 详情=${detail.take(160)}")
            }
        }

        private fun request(method: String, url: String, body: String?, headersJson: String, timeoutMs: Int): String {
            // 歌曲源(如 w.w345.my)的 do.php 风控只放行"干净"请求(原版仅 Host+Accept;
            // 带任何 UA/Connection/Accept-Encoding 头都会被拒)。裸 Socket 只发 Host+JS 头。
            // 任何 HTTP 状态(200/403 等)都直接用裸 Socket 结果,不再用 HttpURLConnection
            // 补发第二次(限流时补发只会双倍放大请求量,加速被源限流)。
            val raw = rawRequest(method, url, body, headersJson, timeoutMs)
            val status = runCatching { JSONObject(raw).optInt("status") }.getOrDefault(0)
            if (status > 0) {
                // 远程日志: 风控/服务端错误(403/5xx)实时上报, 便于排查间歇性点歌失败。
                if (status == 403 || status >= 500) {
                    MiliLog.error("HTTP-$status $method $url")
                }
                // 歌曲源试用窗口/风控到期: 对陌生出口 IP 可能返回 403"检测到异常行为",
                // 换公网 IP 恢复。通知 UI 弹一次提示引导换 IP。
                if (status == 403 && url.contains("/music/do.php")) {
                    Eac19WindowGuard.noteRiskBlocked()
                }
                return raw
            }
            // 仅当裸 Socket 连接失败(status=0)时回退 HttpURLConnection。
            Log.w(TAG, "raw socket connect failed, falling back to HttpURLConnection: $url")
            val fallback = httpRequest(method, url, body, headersJson, timeoutMs)
            val fallbackStatus = runCatching { JSONObject(fallback).optInt("status") }.getOrDefault(0)
            if (fallbackStatus == 0) {
                // 两个通道都失败 → 网络不可达/解析失败, 实时上报。
                val err = runCatching { JSONObject(fallback).optString("error") }.getOrDefault("")
                MiliLog.error("NET-ERR $method $url $err")
            }
            return fallback
        }

        /** HttpURLConnection 通道:JS 未指定 UA/编码/连接时改中性值,避免默认头触发风控。 */
        private fun httpRequest(method: String, url: String, body: String?, headersJson: String, timeoutMs: Int): String {
            var connection: HttpURLConnection? = null
            return try {
                require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported URL" }
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = timeoutMs.coerceIn(1_000, 30_000)
                connection.readTimeout = timeoutMs.coerceIn(1_000, 30_000)
                connection.instanceFollowRedirects = true
                val headers = JSONObject(headersJson.ifBlank { "{}" })
                headers.keys().forEach { name -> connection.setRequestProperty(name, headers.optString(name)) }
                // 歌曲源的 /music/do.php 风控只放行"干净"的请求(抓包只发 Host+Accept):
                // 空 UA、Dalvik、okhttp、Chrome 的 UA 都会返回"检测到异常行为",Go UA 可放行。
                // HttpURLConnection 默认会补 Dalvik UA、Accept-Encoding: gzip、Connection: Keep-Alive,
                // 这里在 JS 未显式指定时全部改成中性值,尽量贴近原版请求。
                if (!headers.has("User-Agent")) connection.setRequestProperty("User-Agent", "Go-http-client/1.1")
                if (!headers.has("Accept-Encoding")) connection.setRequestProperty("Accept-Encoding", "identity")
                if (!headers.has("Connection")) connection.setRequestProperty("Connection", "close")
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                JSONObject().put("status", status).put("body", responseBody).toString()
            } catch (error: Throwable) {
                Log.w(TAG, "$method request failed: ${error.message}")
                JSONObject().put("status", 0).put("body", "").put("error", error.message.orEmpty()).toString()
            } finally {
                connection?.disconnect()
            }
        }

        /** 裸 Socket 通道:只发 Host + JS 指定的请求头,不再补任何默认头(UA/Connection/Accept-Encoding)。 */
        private fun rawRequest(method: String, url: String, body: String?, headersJson: String, timeoutMs: Int): String {
            var socket: Socket? = null
            return try {
                require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported URL" }
                val isHttps = url.startsWith("https://")
                val uri = URL(url).toURI()
                val port = if (uri.port > 0) uri.port else if (isHttps) 443 else 80
                socket = if (isHttps) {
                    (SSLSocketFactory.getDefault().createSocket(uri.host, port) as SSLSocket).apply { startHandshake() }
                } else {
                    Socket(uri.host, port)
                }
                socket.soTimeout = timeoutMs.coerceIn(1_000, 30_000)
                val path = if (uri.rawQuery != null) "${uri.rawPath}?${uri.rawQuery}" else uri.rawPath
                val requestText = StringBuilder()
                requestText.append("$method $path HTTP/1.1\r\n")
                requestText.append("Host: ${uri.host}\r\n")
                val headers = JSONObject(headersJson.ifBlank { "{}" })
                headers.keys().forEach { name -> requestText.append("$name: ${headers.optString(name)}\r\n") }
                val bodyBytes = body?.toByteArray(Charsets.UTF_8)
                if (bodyBytes != null) requestText.append("Content-Length: ${bodyBytes.size}\r\n")
                requestText.append("\r\n")
                val out = socket.getOutputStream()
                out.write(requestText.toString().toByteArray(Charsets.UTF_8))
                if (bodyBytes != null) out.write(bodyBytes)
                out.flush()

                val input = BufferedInputStream(socket.getInputStream())
                val statusLine = readAsciiLine(input)
                val status = statusLine?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
                var contentLength = -1L
                var chunked = false
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim().lowercase(Locale.ROOT)
                        val value = line.substring(idx + 1).trim()
                        if (name == "content-length") contentLength = value.toLongOrNull() ?: -1L
                        if (name == "transfer-encoding" && value.contains("chunked")) chunked = true
                    }
                }
                val responseBody = when {
                    chunked -> readChunkedBody(input)
                    contentLength >= 0 -> String(readExact(input, contentLength), Charsets.UTF_8)
                    else -> input.readBytes().toString(Charsets.UTF_8)
                }
                JSONObject().put("status", status).put("body", responseBody).toString()
            } catch (error: Throwable) {
                Log.w(TAG, "raw request failed: ${error.message}")
                JSONObject().put("status", 0).put("body", "").put("error", error.message.orEmpty()).toString()
            } finally {
                try { socket?.close() } catch (ignored: Exception) {}
            }
        }

        private fun readAsciiLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) {
                    if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                sb.append(b.toChar())
            }
        }

        private fun readExact(input: InputStream, length: Long): ByteArray {
            val result = ByteArray(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            var off = 0
            while (off < result.size) {
                val n = input.read(result, off, result.size - off)
                if (n < 0) break
                off += n
            }
            return if (off == result.size) result else result.copyOf(off)
        }

        private fun readChunkedBody(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readAsciiLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: 0
                if (size <= 0) break
                out.write(readExact(input, size.toLong()))
                readAsciiLine(input) // 吃掉 chunk 尾部 CRLF
            }
            return out.toString(Charsets.UTF_8)
        }
    }

    fun destroy() {
        destroyed = true
        ready = false
        callbacks.keys.toList().forEach { complete(it, null) }
        mainHandler.post {
            webView?.removeJavascriptInterface("android")
            webView?.destroy()
            webView = null
        }
    }
}
