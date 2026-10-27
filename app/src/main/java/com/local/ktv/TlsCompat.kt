package com.local.ktv

import android.content.Context
import android.util.Log
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 低版本 Android 的 TLS 信任补丁。
 *
 * **背景**：`*.cherryonline.cn`（曲库同步 `mconn` / 点歌登录 `mws`）使用 Let's Encrypt 新链：
 * `leaf ← YR2 ← Root YR ← ISRG Root X1`（以上仅 cherryonline 域；自建 Gitea 走 http://ck.ac19.cn:6699 明文，不经 TLS）。
 * `res/xml/network_security_config.xml` 里虽然把这三个根证书加了信任锚，但
 * **该配置只在 Android 7.0(API 24) 及以上被系统读取**；Android 5.1.1(API 22) 上被完全忽略，
 * 系统信任库又没有 ISRG 新根 → `SSLHandshakeException: Trust anchor for certification path not found`
 * → 曲库登录失败 → 「不能下载曲库」。这也解释了为什么只有老设备出问题（其它设备都是 API 24+）。
 *
 * **做法**（只对 API < 24 生效，新系统保持原样）：
 * 1. [install] 构造「系统默认信任 ∪ 应用内置根证书」的复合 X509TrustManager，装到全局
 *    `HttpsURLConnection` 默认 SSLSocketFactory 上 —— 覆盖 App 内所有 `HttpsURLConnection`
 *    （曲库同步、JS 热更新下载、更新检查、头像下载等）。
 *    **主机名校验保持系统默认，不关闭、不放行任意证书。**
 *    API < 20 时再额外套一层 [ProtocolEnablingFactory]，把默认关闭的 **TLSv1.2** 打开
 *    （4.4 的 Conscrypt 支持但默认只用 TLSv1.0，现代服务端会直接拒绝握手）。
 * 2. WebView 的 TLS 信任无法用 SSLContext 覆盖，故提供 [isHostTrustedByOurStore]：
 *    用上面增强后的信任库**独立地对同一主机发起一次 TLS 校验**；只有当它校验通过
 *    （说明证书链本身合法、主机名也匹配，WebView 失败纯粹是因为老系统的根证书缺失）时，
 *    才允许 WebView 继续。**不做「见到 SSL 错误就放行」的盲目信任。**
 */
object TlsCompat {
    private const val TAG = "TlsCompat"

    /** 应用内置的根/中间证书（与 network_security_config.xml 中列出的保持一致）。 */
    private val BUNDLED_CERTS = intArrayOf(
        // ── Let's Encrypt 2026 新链:mconn.cherryonline.cn(曲库登录/同步 mls-api) ──
        R.raw.isrg_root_x1,
        R.raw.isrg_root_yr,
        R.raw.letsencrypt_yr1,
        // ── 沃通/Sectigo 链:ali.pri.mcdn.cherryonline.cn(曲库文件实际存放的阿里云 OSS) ──
        // 曲库 gz 的 files[0].url 落在这台 OSS 主机上,其链是
        //   leaf ← WoTrus RSA DV SSL CA 2 ← Sectigo Root R46 ← USERTrust RSA(锚点)
        // 老系统(尤其 API19 模拟器/2013 年固件的信任库)既没有 Sectigo R46 也没有可靠的
        // USERTrust RSA 路径,同样报 "Trust anchor for certification path not found"。
        // 表现为:登录/同步都成功(mconn 是 LE 链),到下载文件这一步才握手失败。
        // 三张都内置:任一成为锚点即可建链,证书轮换(换 WoTrus/换根)也兜得住。
        R.raw.wotrus_rsa_dv_ssl_ca_2,
        R.raw.sectigo_root_r46,
        R.raw.usertrust_rsa_ca,
    )

    @Volatile private var installed = false

    /** 「系统信任 ∪ 内置根证书」的 SSLSocketFactory，供裸 Socket 通道复用。 */
    @Volatile private var patchedFactory: SSLSocketFactory? = null

    /**
     * 给**裸 Socket** 通道用的 SSLSocketFactory。
     *
     * ⚠️ 必须单独提供：`HttpsURLConnection.setDefaultSSLSocketFactory` **不会**影响
     * `SSLSocketFactory.getDefault()`，而 `KtvJsBridge.rawRequest()` 的 HTTPS 分支正是用后者
     * （`network_security_config` 也只作用于 HttpsURLConnection / WebView，管不到裸 socket）。
     * 未初始化时回退系统默认。
     */
    fun sslSocketFactory(): SSLSocketFactory =
        patchedFactory ?: (SSLSocketFactory.getDefault() as SSLSocketFactory)

    /** 主机 → 是否已被增强信任库校验通过（只缓存肯定结果）。 */
    private val trustedHosts = ConcurrentHashMap<String, Boolean>()

    /** 是否跑在需要补丁的老系统上（API < 24 才会忽略 network_security_config）。 */
    val needsLegacyPatch: Boolean get() = android.os.Build.VERSION.SDK_INT < 24

    fun install(context: Context) {
        if (installed) return
        installed = true
        runCatching {
            val system = systemTrustManager()
            val bundled = bundledTrustManager(context)
            if (bundled == null) {
                Log.w(TAG, "no bundled certs loaded; keep system trust only")
                return
            }
            val composite = CompositeTrustManager(system, bundled)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(composite), null)
            val raw = sslContext.socketFactory as SSLSocketFactory
            // API < 20 默认只启用 TLSv1.0；必须显式打开 TLSv1.2，否则现代服务端直接拒握手。
            val factory = if (android.os.Build.VERSION.SDK_INT < 20) ProtocolEnablingFactory(raw) else raw
            // 裸 Socket 通道始终使用它（系统信任 + 内置根证书，二者取并集）。
            patchedFactory = factory
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                // 新系统由 network_security_config 负责 HttpsURLConnection，无需接管默认工厂。
                Log.i(TAG, "API>=24, keep system default for HttpsURLConnection")
                return
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(factory)
            Log.i(
                TAG,
                "legacy TLS trust installed: system(${system?.acceptedIssuers?.size ?: 0} CAs) " +
                    "+ bundled(${BUNDLED_CERTS.size} certs)",
            )
        }.onFailure { Log.e(TAG, "install failed", it) }
    }

    /**
     * 用**增强后的信任库**校验 `https://host/` 的证书链。
     * 返回 true 仅当 TLS 握手与主机名校验都通过（HttpsURLConnection 的默认主机名校验保持开启）。
     */
    fun isHostTrustedByOurStore(host: String): Boolean {
        if (host.isBlank()) return false
        trustedHosts[host]?.let { return it }
        val ok = runCatching {
            val conn = (URL("https://$host/").openConnection() as HttpsURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "HEAD"
                instanceFollowRedirects = false
            }
            try {
                // 任何响应码都说明握手 + 主机名校验通过了。
                conn.responseCode
                true
            } catch (e: SSLException) {
                Log.w(TAG, "host $host NOT trusted by our store: ${e.message}")
                false
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrDefault(false)
        if (ok) trustedHosts[host] = true
        return ok
    }

    private fun systemTrustManager(): X509TrustManager? = runCatching {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    private fun bundledTrustManager(context: Context): X509TrustManager? = runCatching {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val certificateFactory = CertificateFactory.getInstance("X.509")
        BUNDLED_CERTS.forEachIndexed { index, resId ->
            context.resources.openRawResource(resId).use { input ->
                certificateFactory.generateCertificate(input)?.let {
                    keyStore.setCertificateEntry("ktv-bundled-$index", it)
                }
            }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.onFailure { Log.e(TAG, "load bundled certs failed", it) }.getOrNull()

    /**
     * 让每个新建的 [SSLSocket] 都启用 TLSv1.2（附带 TLSv1.1）。
     *
     * Android 4.4(API 19) 的 Conscrypt 底层**已经支持** TLSv1.2，但默认 `enabledProtocols`
     * 只含 TLSv1.0 —— 结果是握手被现代服务端（Let's Encrypt 全站 TLS1.2+）直接拒绝，
     * 表现为 `SSLHandshakeException: Connection closed by peer` 之类，与证书无关。
     * 同时把「已支持但默认未启用」的 AES-GCM 套件一并打开（老系统上是强套件）。
     * **只做「打开更强选项」，不降级协议、不放行任何证书。**
     */
    private class ProtocolEnablingFactory(
        private val delegate: SSLSocketFactory,
    ) : SSLSocketFactory() {

        private fun tune(socket: Socket): Socket {
            if (socket is SSLSocket) {
                runCatching {
                    val supported = socket.supportedProtocols?.toSet().orEmpty()
                    val want = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1").filter { it in supported }
                    if (want.isNotEmpty()) {
                        socket.enabledProtocols = want.toTypedArray()
                    }
                }.onFailure { Log.w(TAG, "enable TLS1.2 failed: ${it.message}") }
                runCatching {
                    val supported = socket.supportedCipherSuites?.toSet().orEmpty()
                    val current = socket.enabledCipherSuites?.toList().orEmpty()
                    val extra = supported.filter { it.contains("_GCM_") && it !in current }
                    if (extra.isNotEmpty()) {
                        socket.enabledCipherSuites = (current + extra).toTypedArray()
                    }
                }.onFailure { Log.w(TAG, "enable GCM suites failed: ${it.message}") }
            }
            return socket
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = tune(delegate.createSocket())

        override fun createSocket(host: String, port: Int): Socket = tune(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            tune(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket = tune(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            tune(delegate.createSocket(address, port, localAddress, localPort))

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            tune(delegate.createSocket(s, host, port, autoClose))
    }

    /** 系统信任 ∪ 内置根证书：任一通过即放行；都不过则抛出首个错误。 */
    private class CompositeTrustManager(
        private val system: X509TrustManager?,
        private val bundled: X509TrustManager?,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            var first: CertificateException? = null
            for (manager in listOfNotNull(system, bundled)) {
                try {
                    manager.checkClientTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    if (first == null) first = e
                }
            }
            throw first ?: CertificateException("no trust manager available")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            var first: CertificateException? = null
            for (manager in listOfNotNull(system, bundled)) {
                try {
                    manager.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    if (first == null) first = e
                }
            }
            throw first ?: CertificateException("no trust manager available")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            (system?.acceptedIssuers ?: emptyArray()) + (bundled?.acceptedIssuers ?: emptyArray())
    }
}
