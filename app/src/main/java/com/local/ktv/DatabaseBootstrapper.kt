package com.local.ktv

import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 曲库下载引导（仅官方 Muse 同步源）。
 *
 * 说明：已移除分片/镜像数据库下载路径（database_publish 不再发布到代码仓库，
 * 代码仓库此后只存代码）。曲库统一从官方 Muse 服务（mconn.cherryonline.cn mls-api）
 * 同步，由 [MuseDbSync] 负责登录、同步、校验与安装；本类只做版本文件与可读错误包装。
 */
object DatabaseBootstrapper {
    private const val TAG = "DatabaseBootstrapper"

    private val versionFile: File get() = File(MuseDatabase.defaultDbFile().parentFile, "db_version.txt")

    /** 远端曲库版本（官方 Muse）；失败返回 null。 */
    fun fetchRemoteVersion(): String? = MuseDbSync.fetchVersion()

    fun getLocalDbVersion(): String? = runCatching {
        versionFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun saveLocalDbVersion(version: String) {
        runCatching { versionFile.writeText(version.trim()) }
    }

    /** 下载曲库：仅官方 Muse 同步源。 */
    fun download(onProgress: (Int) -> Unit): Result<File> {
        return runCatching {
            val file = MuseDbSync.download(onProgress)
            // MuseDbSync 内部已保存版本；此处幂等兜底。
            MuseDbSync.fetchVersion()?.let(::saveLocalDbVersion)
            file
        }.mapError(::readableError)
    }

    private inline fun <T> Result<T>.mapError(transform: (Throwable) -> Throwable): Result<T> =
        fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(transform(it)) })

    private fun formatSize(bytes: Long): String =
        String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)

    private fun isNoSpaceError(error: Throwable?): Boolean = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.lowercase(Locale.ROOT) }
        .any { "enospc" in it || "no space left" in it }

    private fun readableError(error: Throwable): Throwable {
        if (isNoSpaceError(error)) {
            val target = MuseDatabase.defaultDbFile()
            return IllegalStateException(
                "曲库分区空间不足（当前可用 ${formatSize(target.parentFile?.usableSpace ?: 0L)}），" +
                    "已清理下载临时文件，请释放空间后重试",
                error,
            )
        }
        val message = error.message?.takeIf { it.isNotBlank() } ?: "曲库下载失败"
        Log.w(TAG, "Database download failed: $message")
        return IllegalStateException(message, error)
    }
}
