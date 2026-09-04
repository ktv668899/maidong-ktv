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

/** Small, dependency-free HTTP server used only by devices on the local LAN. */
class LocalRemoteServer(private val context: Context) {
    data class ApiResponse(
        val status: Int = 200,
        val body: String = "",
        val contentType: String = "application/json; charset=utf-8",
        val bytes: ByteArray? = null
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
    @Volatile private var lastError: String? = null
    private var server: ServerSocket? = null
    private var callback: Callback? = null

    @Synchronized
    fun start(address: String?, callback: Callback): Boolean {
        if (running && address == boundAddress) return true
        stop()
        this.callback = callback
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
    fun error(): String? = lastError
    fun url(): String? = if (running && boundAddress != null && boundPort > 0) {
        "http://$boundAddress:$boundPort/mobile"
    } else null

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = 5000
            socket.use { client ->
                val remote = client.inetAddress
                if (!isPrivateAddress(remote)) {
                    write(client, ApiResponse(403, error("FORBIDDEN", "仅允许局域网设备访问")))
                    return
                }
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
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val body = String(bodyBytes, 0, offset, Charsets.UTF_8)
                val response = when {
                    path == "/" || path == "/mobile" || path == "/mobile/" -> asset("mobile/index.html", "text/html; charset=utf-8")
                    path == "/mobile/app.css" -> asset("mobile/app.css", "text/css; charset=utf-8")
                    path == "/mobile/logo.css" -> asset("mobile/logo.css", "text/css; charset=utf-8")
                    path == "/mobile/app.js" -> asset("mobile/app.js", "application/javascript; charset=utf-8")
                    path == "/res/drawable-nodpi/ktv_logo.png" -> logoAsset()
                    path.startsWith("/api/v1/") -> callback?.handle(method, path, query, body)
                        ?: ApiResponse(503, error("NOT_READY", "服务尚未准备完成"))
                    else -> ApiResponse(404, error("NOT_FOUND", "接口不存在"))
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
        return name == expected || isPrivateIpv4(name)
    }

    private fun validOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank() || origin == "null") return true
        return runCatching {
            val uri = URI(origin)
            uri.scheme == "http" && uri.port == boundPort && isPrivateIpv4(uri.host)
        }.getOrDefault(false)
    }

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
        val output = socket.getOutputStream()
        output.write(("HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'\r\n" +
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
