package com.local.ktv

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程日志客户端 —— GlogCenter（gotoeasy/glogcenter）HTTP 接口。
 *
 * 接口：`POST http://log.kasugano.cn/glc/v1/log/add`，`Content-Type: application/json`，
 * 单条 JSON 对象（批量用 `/glc/v1/log/addBatch`，此处按单条上报）。
 *
 * 字段（均为字符串，对应 GlogCenter 页面列）：
 *   system      系统名（本 App 固定 [SYSTEM]）
 *   date        日期时间，格式 `yyyy-MM-dd HH:mm:ss.SSS`（务必与检索格式一致）
 *   text        日志内容
 *   servername  主机名（设备型号）
 *   serverip    主机 IP（设备局域网 IP）
 *   loglevel    日志级别（INFO / WARN / ERROR）
 *   traceid     追踪码（此处用设备标识）
 *   user        用户（此处用设备标识）
 *
 * 策略：单后台线程 + 有界队列；发送失败重试一次，连续失败退避；尽力而为，不阻塞业务。
 * 日志事件：启动设备/网络信息、点歌命中的源与结果、接口 403/网络错误、下载/播放失败。
 */
object MiliLog {
    private const val TAG = "MiliLog"
    private const val ENDPOINT = "http://log.kasugano.cn/glc/v1/log/add"

    /** GlogCenter 页面上的「系统名」，用于区分应用。 */
    private const val SYSTEM = "maidongktv"
    private const val MAX_TEXT_LEN = 900
    private const val MAX_QUEUE = 300
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000
    private const val RETRY_SLEEP_MS = 1500L
    private const val FAILURE_BACKOFF_MS = 10_000L

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE)
    private var senderThread: Thread? = null

    @Volatile private var serverName: String = "android"
    @Volatile private var serverIp: String = ""
    @Volatile private var traceId: String = ""
    @Volatile private var user: String = ""

    /** 用 app context 初始化并启动发送线程。可重复调用。 */
    fun init(context: Context) {
        if (started.getAndSet(true)) return
        serverName = sanitize(Build.MODEL).ifEmpty { "android" }
        running.set(true)
        senderThread = Thread({ sendLoop() }, "mili-glog").also { it.isDaemon = true; it.start() }
    }

    /** 更新上报用的设备网络/身份字段（IP 变化或重新登录时调用）。 */
    fun updateDeviceContext(ip: String? = null, deviceId: String? = null) {
        if (!ip.isNullOrBlank()) serverIp = ip
        if (!deviceId.isNullOrBlank()) {
            user = deviceId
            traceId = deviceId
        }
    }

    fun info(msg: String) = enqueue("INFO", msg)
    fun notice(msg: String) = enqueue("INFO", msg)
    fun warn(msg: String) = enqueue("WARN", msg)
    fun error(msg: String) = enqueue("ERROR", msg)

    private fun enqueue(level: String, msg: String) {
        if (!started.get()) return
        val body = buildBody(level, msg) ?: return
        // 队列满时丢最旧，保证新日志能进队。
        if (!queue.offer(body)) {
            queue.poll()
            queue.offer(body)
        }
    }

    private fun buildBody(level: String, msg: String): String? {
        val text = msg.replace(Regex("[\\r\\n]+"), " ").trim()
        if (text.isEmpty()) return null
        val truncated = if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_LEN) {
            text.take(MAX_TEXT_LEN)
        } else {
            text
        }
        return runCatching {
            JSONObject().apply {
                put("system", SYSTEM)
                put("date", timestamp())
                put("text", truncated)
                put("servername", serverName)
                put("serverip", serverIp)
                put("loglevel", level)
                put("traceid", traceId)
                put("user", user)
            }.toString()
        }.getOrNull()
    }

    /** GlogCenter 检索要求的时间格式：yyyy-MM-dd HH:mm:ss.SSS */
    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else ' ' }
            .joinToString("").trim()

    // ─── 发送线程 ────────────────────────────────────

    private fun sendLoop() {
        var consecutiveFailures = 0
        while (running.get()) {
            val body: String = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }
            var sent = post(body)
            if (!sent) {
                sleepQuietly(RETRY_SLEEP_MS)
                sent = post(body)
            }
            if (sent) {
                consecutiveFailures = 0
                // 顺手把已积压的日志发完。
                var next: String? = queue.poll()
                while (next != null) {
                    post(next)
                    next = queue.poll()
                }
            } else {
                consecutiveFailures++
                Log.w(TAG, "glog send dropped (failures=$consecutiveFailures): ${body.take(120)}")
                if (consecutiveFailures >= 3) {
                    consecutiveFailures = 0
                    sleepQuietly(FAILURE_BACKOFF_MS)
                }
            }
        }
    }

    private fun post(body: String): Boolean = try {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "MaidongKTV/${BuildConfig.VERSION_NAME}")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            // 读取并丢弃响应体，确保请求完成（也便于排查）。
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            runCatching { stream?.use { it.readBytes() } }
            code in 200..299
        } finally {
            connection.disconnect()
        }
    } catch (e: Throwable) {
        Log.w(TAG, "glog send error: ${e.message}")
        false
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 停止发送（退出时调用）。 */
    fun shutdown() {
        running.set(false)
        senderThread?.interrupt()
        senderThread = null
    }
}
