package com.local.ktv

import android.content.Context
import android.util.Log
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
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
 * 2. WebView 的 TLS 信任无法用 SSLContext 覆盖，故提供 [isHostTrustedByOurStore]：
 *    用上面增强后的信任库**独立地对同一主机发起一次 TLS 校验**；只有当它校验通过
 *    （说明证书链本身合法、主机名也匹配，WebView 失败纯粹是因为老系统的根证书缺失）时，
 *    才允许 WebView 继续。**不做「见到 SSL 错误就放行」的盲目信任。**
 */
object TlsCompat {
    private const val TAG = "TlsCompat"

    /** 应用内置的根/中间证书（与 network_security_config.xml 中列出的保持一致）。 */
    private val BUNDLED_CERTS = intArrayOf(
        R.raw.isrg_root_x1,
        R.raw.isrg_root_yr,
        R.raw.letsencrypt_yr1,
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
            val factory = sslContext.socketFactory as SSLSocketFactory
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
