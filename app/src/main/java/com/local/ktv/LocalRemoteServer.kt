package com.local.ktv

import android.content.Context
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small, dependency-free HTTP server for the mobile song-request web app.
 * 局域网设备免令牌直连; 配置公网域名(路由器 DDNS + 端口映射)后, 外网设备须持
 * 二维码里的访问令牌(?t= / Cookie)才能打开页面与调用接口。
 */
class LocalRemoteServer(private val context: Context) {
    data class ApiResponse(
        val status: Int = 200,
        val body: String = "",
        val contentType: String = "application/json; charset=utf-8",
        val bytes: ByteArray? = null,
        val headers: Map<String, String> = emptyMap()
    )

    interface Callback {
        fun statusJson(): String
        fun catalogJson(): String
        fun queueJson(): String
        fun rankJson(): String
        fun filterJson(type: String?, value: String?): String
        fun favoritesJson(): String
        fun search(query: String?): String
        fun order(query: String?): String
        fun command(action: String?, params: Map<String, String>): String
        fun handle(method: String, path: String, query: Map<String, String>, body: String): ApiResponse =
            ApiResponse(404, "{\"ok\":false,\"code\":\"NOT_FOUND\",\"error\":\"接口不存在\"}")
    }

    private var pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var boundAddress: String? = null
    @Volatile private var boundPort = -1
    /** 公网访问域名(如 DDNS 域名), 非空时二维码/Host/Origin 校验放行该域名。 */
    @Volatile private var publicHost: String? = null
    /** 外网客户端访问令牌(二维码 ?t= 携带); 局域网客户端免令牌。 */
    @Volatile private var accessKey: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var lastActivityAtMs = 0L
    private var server: ServerSocket? = null
    private var callback: Callback? = null

    @Synchronized
    fun start(address: String?, publicHost: String?, accessKey: String?, callback: Callback): Boolean {
        if (running && address == boundAddress &&
            publicHost?.trim() == this.publicHost && accessKey?.trim() == this.accessKey
        ) return true
        stop()
        this.callback = callback
        this.publicHost = publicHost?.trim()?.takeIf { it.isNotEmpty() }
        this.accessKey = accessKey?.trim()?.takeIf { it.isNotEmpty() }
        if (!isPrivateIpv4(address)) {
            lastError = "请先连接 Wi-Fi 或有线局域网"
            return false
        }
        if (pool.isShutdown) pool = Executors.newCachedThreadPool()
        var candidate: ServerSocket? = null
        for (port in 8765..8775) {
            try {
                candidate = ServerSocket(port, 32)
                boundPort = port
                break
            } catch (_: Exception) {
            }
        }
        if (candidate == null) {
            lastError = "端口 8765–8775 均被占用"
            return false
        }
        server = candidate
        boundAddress = address
        lastError = null
        running = true
        pool.execute {
            try {
                while (running) {
                    val socket = server?.accept() ?: break
                    pool.execute { handle(socket) }
                }
            } catch (e: Exception) {
                if (running) lastError = e.message ?: "本地服务异常"
            } finally {
                running = false
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        boundPort = -1
        boundAddress = null
        pool.shutdownNow()
    }

    fun isRunning(): Boolean = running
    fun port(): Int = boundPort
    fun address(): String? = boundAddress
    fun publicHostOrNull(): String? = publicHost
    fun error(): String? = lastError

    /** 是否在 [withinMs] 内有手机端请求(扫码点歌页面可见时每秒轮询 state)。 */
    fun hasRecentActivity(withinMs: Long): Boolean {
        val last = lastActivityAtMs
        return last > 0 && System.currentTimeMillis() - last <= withinMs
    }

    fun url(): String? = if (running && boundAddress != null && boundPort > 0) {
        "http://$boundAddress:$boundPort/mobile"
    } else null

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = 5000
            socket.use { client ->
                val remote = client.inetAddress
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    write(client, ApiResponse(400, error("BAD_REQUEST", "请求格式错误")))
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).lowercase(Locale.US)] = line.substring(colon + 1).trim()
                }
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                // 局域网设备免令牌直连; 外网设备必须持二维码里的访问令牌(?t= 或 Cookie),
                // 否则只读静态资源可见, 页面与接口一律拒绝——公网端口映射后防止陌生人操控。
                val lanClient = isPrivateAddress(remote)
                val key = accessKey
                val tokenOk = key.isNullOrEmpty() ||
                    query["t"] == key ||
                    cookieValue(headers["cookie"], "mtk") == key
                val pageRequest = path == "/" || path == "/mobile" || path == "/mobile/"
                if (!lanClient && !key.isNullOrEmpty() && !tokenOk) {
                    if (pageRequest) {
                        write(client, ApiResponse(403, qrExpiredHtml(), "text/html; charset=utf-8"))
                    } else {
                        write(client, ApiResponse(403, error("FORBIDDEN", "访问令牌无效，请在电视上重新扫码")))
                    }
                    return
                }
                if (!validHost(headers["host"]) || !validOrigin(headers["origin"])) {
                    write(client, ApiResponse(403, error("BAD_ORIGIN", "请求来源无效")))
                    return
                }
                val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 65536) ?: 0
                val bodyBytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bodyBytes, offset, length - offset)
                    if (count < 0) break
                    offset += count
                }
                val body = String(bodyBytes, 0, offset, Charsets.UTF_8)
                // 手机端已成功建立会话并访问页面/接口 → 记录活跃(用于控制二维码显隐)。
                lastActivityAtMs = System.currentTimeMillis()
                var response = when {
                    pageRequest -> asset("mobile/index.html", "text/html; charset=utf-8")
                    path == "/mobile/app.css" -> asset("mobile/app.css", "text/css; charset=utf-8")
                    path == "/mobile/logo.css" -> asset("mobile/logo.css", "text/css; charset=utf-8")
                    path == "/mobile/app.js" -> asset("mobile/app.js", "application/javascript; charset=utf-8")
                    path == "/res/drawable-nodpi/ktv_logo.png" -> logoAsset()
                    path.startsWith("/api/v1/") -> callback?.handle(method, path, query, body)
                        ?: ApiResponse(503, error("NOT_READY", "服务尚未准备完成"))
                    else -> ApiResponse(404, error("NOT_FOUND", "接口不存在"))
                }
                // 外网客户端经 ?t= 首次打开页面时种下 Cookie, 后续接口请求免重复带令牌。
                if (pageRequest && !key.isNullOrEmpty() && query["t"] == key &&
                    cookieValue(headers["cookie"], "mtk") != key
                ) {
                    response = response.copy(
                        headers = response.headers + ("Set-Cookie" to "mtk=$key; Path=/; Max-Age=31536000; HttpOnly")
                    )
                }
                write(client, response)
            }
        }
    }

    private fun asset(name: String, type: String): ApiResponse = try {
        val text = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        ApiResponse(200, text, type)
    } catch (_: Exception) {
        ApiResponse(404, error("ASSET_NOT_FOUND", "页面资源缺失"))
    }

    private fun logoAsset(): ApiResponse = try {
        val bytes = context.resources.openRawResource(R.drawable.ktv_logo).use { it.readBytes() }
        ApiResponse(200, contentType = "image/png", bytes = bytes)
    } catch (_: Exception) {
        ApiResponse(404, error("ASSET_NOT_FOUND", "Logo 资源缺失"))
    }

    private fun validHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val expected = boundAddress ?: return false
        val name = host.substringBefore(':')
        if (name == expected || isPrivateIpv4(name)) return true
        val pub = publicHost
        return !pub.isNullOrEmpty() && name.equals(pub, ignoreCase = true)
    }

    private fun validOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank() || origin == "null") return true
        return runCatching {
            val uri = URI(origin)
            if (uri.scheme != "http" || uri.port != boundPort) return@runCatching false
            val host = uri.host ?: return@runCatching false
            if (isPrivateIpv4(host)) return@runCatching true
            val pub = publicHost
            !pub.isNullOrEmpty() && host.equals(pub, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun cookieValue(header: String?, name: String): String? {
        if (header.isNullOrBlank()) return null
        for (part in header.split(';')) {
            val segment = part.trim()
            val eq = segment.indexOf('=')
            if (eq > 0 && segment.substring(0, eq).trim() == name) return segment.substring(eq + 1).trim()
        }
        return null
    }

    /** 外网客户端未带令牌访问页面时给出的可读提示(替代裸 JSON)。 */
    private fun qrExpiredHtml(): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>二维码已失效</title></head>" +
            "<body style=\"font-family:sans-serif;text-align:center;padding-top:20vh;color:#333\">" +
            "<h2>二维码已失效</h2><p>请在电视上重新打开「扫码点歌」后再次扫码</p></body></html>"

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun parseQuery(query: String): Map<String, String> = buildMap {
        if (query.isBlank()) return@buildMap
        query.split('&').forEach { part ->
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            put(key, value)
        }
    }

    private fun write(socket: Socket, response: ApiResponse) {
        val bytes = response.bytes ?: response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 400 -> "Bad Request"
            403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error"
        }
        val extraHeaders = response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        val output = socket.getOutputStream()
        output.write(("HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'\r\n" +
            extraHeaders +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        output.write(bytes)
        output.flush()
    }

    companion object {
        fun isPrivateIpv4(value: String?): Boolean = runCatching {
            val address = InetAddress.getByName(value)
            address is Inet4Address && isPrivateAddress(address)
        }.getOrDefault(false)

        private fun isPrivateAddress(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            val b = address.address
            return b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
        }

        private fun error(code: String, message: String): String =
            "{\"ok\":false,\"code\":\"$code\",\"error\":\"$message\"}"
    }
}
