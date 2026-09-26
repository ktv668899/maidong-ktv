package com.local.ktv

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher

/**
 * 官方曲库同步：login → cookie → sqlite/sync → 下载 gz → 解压安装。
 *
 * 这是原版「时光KTV / 麦动」的曲库分发方式，取代此前临时使用的分片分发方案。
 * 授权门控发生在服务端(未授权设备 sync 返回空 files)，本地只负责完整复刻请求协议。
 */
object MuseDbSync {
    private const val TAG = "MuseDbSync"
    private const val LOGIN_URL = "https://mconn.cherryonline.cn/mls-api/v1/login"
    private const val SYNC_URL = "https://mconn.cherryonline.cn/mls-api/v1/sqlite/sync"
    private const val RSA_PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAucL0oFErd7REM6TSNa3EZdN1YaOs4J1eCybLPyoQ9ru3q1HU67agC9FzhrCG/RvAQUya5iPmQ8Caed05vqcCVcyJChmkSOGQ7DVShe2rGuTMNlpoRV6UzfcraaVS++7m2K/+kSZJ8OAhhhVuqPruMjsFYpdtstAwvyZT28b+eENwzpp9UHqsooZc7FZ0H8kTbs6XMkw4nIWo+4HoPAhNLEY+xdHvwY6drF/3WDTvsaoMrs73TVQCEEHzZNIz2H/is9VLMnIyOfnfcJi9br78Fj2xHzxu3sAySBOTVLmUMxqYh/g1ox5OXGcW93HJkQLkBi42tFAEkWYlYyl93+jbbQIDAQAB"
    private const val MUSIC_FILE_TYPE = 214
    private const val CHANNEL = "common"
    private const val MODE = "ott"
    private const val IP = "172.16.32.15"

    private data class DbFile(val url: String, val md5: String, val version: String)

    private val target: File get() = MuseDatabase.defaultDbFile()

    // 原版「时光KTV」mls-api 登录使用的设备标识(已验证:用该 id 从任意网络
    // 调 sqlite/sync 都能返回文件;随机 id 或 b 前缀的 mad-api box id 均返回空)。
    // 注意 muse_open=0 并不阻止下载,真正门槛是 mls 登录的 device_id。
    private val deviceId: String = "s00e04b021b2d"

    /** 取远端曲库版本；失败返回 null。版本取自 files[0].date(毫秒时间戳)。 */
    fun fetchVersion(): String? = runCatching {
        val cookie = login() ?: return null
        val file = sync(cookie) ?: return null
        file.version
    }.getOrNull()

    /** 下载并安装曲库。失败抛异常，由调用方决定是否回退到其它源。 */
    fun download(onProgress: (Int) -> Unit): File {
        val cookie = login() ?: error("登录曲库服务失败")
        val file = sync(cookie) ?: error("曲库同步失败或无可用文件")
        val version = file.version

        val gzFile = File(target.parentFile, "${target.name}.gz.download")
        val tmpDb = File(target.parentFile, "${target.name}.download")
        try {
            downloadFile(file.url, gzFile) { pct -> onProgress((pct * 80 / 100).coerceIn(0, 80)) }
            onProgress(80)
            if (file.md5.isNotEmpty()) {
                check(md5(gzFile).equals(file.md5, ignoreCase = true)) { "曲库文件校验失败" }
            }
            onProgress(84)
            GZIPInputStream(gzFile.inputStream().buffered(256 * 1024)).use { gz ->
                BufferedOutputStream(FileOutputStream(tmpDb)).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = gz.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
            }
            onProgress(96)
            val backup = File(target.parentFile, "${target.name}.backup")
            backup.delete()
            if (target.exists()) check(target.renameTo(backup)) { "无法备份旧曲库" }
            if (!tmpDb.renameTo(target)) {
                backup.renameTo(target)
                error("无法安装下载的曲库")
            }
            backup.delete()
            saveVersion(version)
            onProgress(100)
            return target
        } finally {
            gzFile.delete()
        }
    }

    private fun saveVersion(version: String) {
        runCatching { File(target.parentFile, "db_version.txt").writeText(version) }
    }

    private fun login(): String? {
        val encrypted = rsaEncrypt(RSA_PUBLIC_KEY, """{"device_id":"$deviceId"}""")
        val body = JSONObject()
            .put("encrypted_data", encrypted)
            .put("ip", IP)
            .put("dns", "")
            .put("router", "")
            .put("subnet_mask", "255.255.255.0")
            .put("channel", CHANNEL)
            .put("mode", MODE)
            .toString()
        return postForCookie(LOGIN_URL, body)
    }

    private fun sync(cookie: String): DbFile? {
        val body = JSONObject()
            .put("last_sync_at", 0)
            .put("music_file_type", MUSIC_FILE_TYPE)
            .toString()
        val resp = postJson(SYNC_URL, body, cookie) ?: return null
        val files = resp.optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val f = files.getJSONObject(0)
        val url = f.optString("url")
        if (url.isBlank()) return null
        val version = f.optLong("date", 0L).takeIf { it > 0 }?.toString()
            ?: f.optString("day").takeIf(String::isNotBlank)
            ?: f.optLong("db_vc", 0L).takeIf { it > 0 }?.toString()
            ?: "0"
        return DbFile(url, f.optString("md5"), version)
    }

    /** POST 并捕获 Set-Cookie 中的 muse-portal-connector。 */
    private fun postForCookie(url: String, jsonBody: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", "MaidongKTV/1.2")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                MiliLog.warn("MUSE-DB login HTTP ${conn.responseCode} (url=$url)")
                return null
            }
            conn.inputStream.use { it.readBytes() }
            conn.getHeaderField("Set-Cookie")
                ?.substringBefore(';')
                ?.takeIf { it.startsWith("muse-portal-connector=") }
        } catch (error: Throwable) {
            Log.w(TAG, "login failed: ${error.message}")
            // 老设备上最常见的就是证书链校验失败（SSLHandshakeException），必须上报。
            MiliLog.warn("MUSE-DB login error: ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun postJson(url: String, jsonBody: String, cookie: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", "MaidongKTV/1.2")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                MiliLog.warn("MUSE-DB sync HTTP ${conn.responseCode} (url=$url)")
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text)
        } catch (error: Throwable) {
            Log.w(TAG, "sync failed: ${error.message}")
            MiliLog.warn("MUSE-DB sync error: ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MaidongKTV/1.2")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code" }
            val total = conn.contentLengthCompat()
            conn.inputStream.buffered(256 * 1024).use { input ->
                dest.outputStream().buffered(256 * 1024).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        read += count
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 99))
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun rsaEncrypt(publicKeyBase64: String, plaintext: String): String = runCatching {
        val keyBytes = Base64.decode(publicKeyBase64.replace("\\s".toRegex(), ""), Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrDefault("")

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
