package com.local.ktv

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedOutputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * 远程日志客户端 —— 复刻为内网/私有部署的 syslog 服务器(zb.kasugano.cn:514)。
 *
 * - 传输: TCP + TLS(信任打包的 raw/mili_log.crt 根证书, 服务器为 Let's Encrypt
 *   签发链 ISRG Root X1, 故该根即可完成链校验; 连接目标域名与证书 SAN 不同,
 *   属私有部署惯例, 因此只做链校验、不做主机名校验)。
 * - 格式: BSD syslog RFC 3164 —— <PRI>MMM dd HH:mm:ss HOSTNAME TAG: message
 * - 策略: 单后台线程 + 有界队列; 断线自动重连; 尽力而为, 失败不阻塞业务。
 *
 * 日志事件:
 *   1) App 启动时上报设备/网络信息;
 *   2) 点歌解析歌曲源(w.w345.my / 实际下载域名)时实时上报;
 *   3) 网络错误/接口 403/下载失败/播放失败实时上报。
 */
object MiliLog {
    private const val TAG = "MiliLog"
    private const val HOST = "zb.kasugano.cn"
    private const val PORT = 514
    private const val TAG_NAME = "MaidongKTV"
    private const val FACILITY = 16 // local0
    private const val MAX_MSG_BYTES = 900
    private const val MAX_QUEUE = 300
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val SOCKET_TIMEOUT_MS = 10000
    private const val RETRY_SLEEP_MS = 10_000L

    // RFC 3164 severity
    private const val SEV_ERROR = 3
    private const val SEV_WARNING = 4
    private const val SEV_NOTICE = 5
    private const val SEV_INFO = 6

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE)
    private var sslContext: SSLContext? = null
    private var hostname = "android"

    @Volatile private var socket: SSLSocket? = null
    private var senderThread: Thread? = null

    /** 用 app context 初始化(加载打包证书、读取设备标识)。可重复调用。 */
    fun init(context: Context) {
        if (started.getAndSet(true)) return
        val appContext = context.applicationContext
        hostname = sanitize(Build.MODEL).ifEmpty { "android" }
        sslContext = buildSslContext(appContext)
        running.set(true)
        senderThread = Thread({ sendLoop() }, "mili-syslog").also { it.isDaemon = true; it.start() }
    }

    private fun sanitize(s: String): String =
        s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else ' ' }
            .joinToString("").trim().replace(Regex("\\s+"), "-")

    private fun buildSslContext(context: Context): SSLContext? = try {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = context.resources.openRawResource(R.raw.mili_log).use {
            cf.generateCertificate(it) as X509Certificate
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)
        keyStore.setCertificateEntry("mili_log", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    } catch (e: Throwable) {
        Log.e(TAG, "buildSslContext failed", e)
        null
    }

    // ─── 对外上报接口 ────────────────────────────────

    fun info(msg: String) = enqueue(SEV_INFO, msg)
    fun notice(msg: String) = enqueue(SEV_NOTICE, msg)
    fun warn(msg: String) = enqueue(SEV_WARNING, msg)
    fun error(msg: String) = enqueue(SEV_ERROR, msg)

    private fun enqueue(severity: Int, msg: String) {
        if (!started.get() || sslContext == null) return
        val line = format(severity, msg)
        if (line == null) return
        // 队列满时丢最旧, 保证新日志能进队。
        if (!queue.offer(line)) {
            queue.poll()
            queue.offer(line)
        }
    }

    private fun format(severity: Int, msg: String): String? {
        val body = msg.replace(Regex("[\\r\\n]+"), " ").trim()
        if (body.isEmpty()) return null
        val truncated = if (body.toByteArray(Charsets.UTF_8).size > MAX_MSG_BYTES) {
            body.take(MAX_MSG_BYTES)
        } else body
        val now = Calendar.getInstance()
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val day = String.format(Locale.US, "%2d", now.get(Calendar.DAY_OF_MONTH))
        val time = String.format(Locale.US, "%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
        val pri = FACILITY * 8 + severity
        // RFC3164: <PRI>TIMESTAMP HOSTNAME TAG: MSG  (TAG 不含空格)
        return "<$pri>${months[now.get(Calendar.MONTH)]} $day $time $hostname $TAG_NAME: $truncated"
    }

    // ─── 发送线程 ────────────────────────────────────

    private fun sendLoop() {
        var consecutiveFailures = 0
        while (running.get()) {
            val line: String = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }
            var sent = false
            var attempt = 0
            while (!sent && attempt < 2) {
                attempt++
                try {
                    val s = connectOrReuse()
                    val out = BufferedOutputStream(s.getOutputStream())
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    out.flush()
                    sent = true
                    consecutiveFailures = 0
                    // 同一连接顺手把队列里已积压的消息发完。
                    var next: String? = queue.poll()
                    while (next != null) {
                        out.write(next.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                        out.flush()
                        next = queue.poll()
                    }
                } catch (e: Throwable) {
                    closeSocket()
                    if (attempt == 1) {
                        Log.w(TAG, "syslog send failed (${e.message}), retrying...")
                        try { Thread.sleep(1500) } catch (ie: InterruptedException) { break }
                    }
                }
            }
            if (!sent) {
                consecutiveFailures++
                Log.w(TAG, "syslog send dropped: $line")
                if (consecutiveFailures >= 3) {
                    consecutiveFailures = 0
                    try { Thread.sleep(RETRY_SLEEP_MS) } catch (ie: InterruptedException) { break }
                }
            }
        }
        closeSocket()
    }

    private fun connectOrReuse(): SSLSocket {
        val existing = socket
        if (existing != null && existing.isConnected && !existing.isClosed && !existing.isOutputShutdown) {
            return existing
        }
        val context = sslContext ?: throw IllegalStateException("SSL context not ready")
        val raw = context.socketFactory.createSocket() as SSLSocket
        raw.connect(java.net.InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        raw.soTimeout = SOCKET_TIMEOUT_MS
        raw.startHandshake()
        socket = raw
        Log.i(TAG, "syslog connected to $HOST:$PORT")
        return raw
    }

    private fun closeSocket() {
        try { socket?.close() } catch (ignored: Exception) {}
        socket = null
    }

    /** 供测试/关闭时调用。 */
    fun shutdown() {
        running.set(false)
        senderThread?.interrupt()
        senderThread = null
        closeSocket()
    }
}
