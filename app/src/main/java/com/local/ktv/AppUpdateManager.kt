package com.local.ktv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Gitea Release based in-app updater. */
object AppUpdateManager {
    private const val LATEST_RELEASE_API =
        "https://gitea.kasugano.cn/api/v1/repos/zijunchiang/maidong-ktv/releases/latest"

    data class Release(
        val version: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
    )

    fun check(currentVersion: String, result: (Result<Release?>) -> Unit) {
        Thread({
            result(runCatching {
                val json = getText(LATEST_RELEASE_API)
                val root = JSONObject(json)
                val version = root.optString("tag_name").trim().removePrefix("v")
                val assets = root.optJSONArray("assets")
                var apkUrl = ""
                var apkName = ""
                if (assets != null) {
                    for (index in 0 until assets.length()) {
                        val asset = assets.optJSONObject(index) ?: continue
                        val name = asset.optString("name")
                        val url = asset.optString("browser_download_url")
                        if (name.endsWith(".apk", ignoreCase = true) || url.contains(".apk", ignoreCase = true)) {
                            apkName = name.ifBlank { "maidong-ktv-$version.apk" }
                            apkUrl = url
                            break
                        }
                    }
                }
                if (version.isBlank() || apkUrl.isBlank() || compareVersions(version, currentVersion) <= 0) null
                else Release(
                    version = version,
                    title = root.optString("name").ifBlank { "麦动 KTV $version" },
                    notes = root.optString("body"),
                    apkUrl = apkUrl,
                    apkName = apkName,
                )
            })
        }, "gitea-update-check").start()
    }

    fun download(
        activity: Activity,
        release: Release,
        progress: (Int) -> Unit,
        result: (Result<File>) -> Unit,
    ) {
        Thread({
            result(runCatching {
                val directory = File(activity.externalCacheDir ?: activity.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "maidong-ktv-${release.version}.apk")
                val partial = File(directory, "${target.name}.download")
                partial.delete()
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MaidongKTV/${BuildConfig.VERSION_NAME}")
                }
                try {
                    require(connection.responseCode in 200..299) { "下载失败：HTTP ${connection.responseCode}" }
                    val total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (total > 0) progress((downloaded * 100 / total).toInt().coerceIn(0, 100))
                            }
                            output.fd.sync()
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                require(partial.length() > 1_000_000L) { "下载的安装包不完整" }
                partial.inputStream().use { input ->
                    require(input.read() == 'P'.code && input.read() == 'K'.code) { "下载内容不是 APK" }
                }
                val info = activity.packageManager.getPackageArchiveInfo(partial.absolutePath, 0)
                require(info?.packageName == activity.packageName) { "安装包应用标识不匹配" }
                target.delete()
                require(partial.renameTo(target)) { "无法保存安装包" }
                target
            })
        }, "gitea-update-download").start()
    }

    /**
     * 直接启动系统 Package Installer 安装 APK。
     *
     * Android 8+ 不再手动跳转 Settings 权限列表 (TV 遥控器难以操作),
     * 而是把 APK URI 交给系统安装器; 若缺少"允许安装未知应用"权限,
     * 系统安装器会自动弹出授权对话框, 用户确认后即可继续安装。
     */
    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ 允许系统安装器直接拿到 URI 权限, 无需 NEW_TASK
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        activity.startActivity(intent)
    }

    internal fun compareVersions(left: String, right: String): Int {
        fun parts(value: String) = value.removePrefix("v").split(Regex("[^0-9]+"))
            .filter(String::isNotEmpty).map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val compared = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (compared != 0) return compared
        }
        return 0
    }

    private fun getText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MaidongKTV/${BuildConfig.VERSION_NAME}")
        }
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "检查更新失败：HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
