package com.local.ktv

import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 曲库下载引导（仅官方 Muse 同步源）。
 *
 * 说明：已移除分片/镜像数据库下载路径（database_publish 不再发布到代码仓库，
 * 代码仓库此后只存代码）。曲库统一从官方 Muse 服务（mconn.cherryonline.cn mls-api）
 * 同步，由 [MuseDbSync] 负责登录、同步、校验与安装；本类负责同步时效、版本文件
 * 与可读错误包装。
 *
 * 同步时效：默认 **30 天**（[SYNC_TTL_DAYS]）。未到期时启动不联网同步，直接用
 * 本地曲库；到期后才向服务器查询版本并在有更新时下载。
 */
object DatabaseBootstrapper {
    private const val TAG = "DatabaseBootstrapper"

    /** 曲库同步时效(天)：达到时效后才触发服务器同步。 */
    const val SYNC_TTL_DAYS = 30
    private const val SYNC_TTL_MS = SYNC_TTL_DAYS * 24L * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val versionFile: File get() = File(MuseDatabase.defaultDbFile().parentFile, "db_version.txt")
    private val syncStampFile: File get() = File(MuseDatabase.defaultDbFile().parentFile, "db_sync_at.txt")

    /** 上次成功同步(或成功校验为最新)的时间戳(毫秒)；无记录返回 0。 */
    fun lastSyncAt(): Long = runCatching {
        syncStampFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
    }.getOrNull() ?: 0L

    /** 记录一次成功同步/校验，作为 30 天时效的起点。 */
    fun markSynced(at: Long = System.currentTimeMillis()) {
        runCatching { syncStampFile.writeText(at.toString()) }
    }

    /** 同步是否已到期；无记录(从未同步)视为到期。 */
    fun isSyncDue(now: Long = System.currentTimeMillis()): Boolean {
        val last = lastSyncAt()
        return last <= 0L || now - last >= SYNC_TTL_MS
    }

    /** 距离下次同步的剩余毫秒；已到期或无记录返回 0。 */
    fun syncRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val last = lastSyncAt()
        if (last <= 0L) return 0L
        return (last + SYNC_TTL_MS - now).coerceAtLeast(0L)
    }

    /** 同步状态文案(用于设置界面展示)。 */
    fun syncStatusText(now: Long = System.currentTimeMillis()): String {
        val last = lastSyncAt()
        if (last <= 0L) return "未同步"
        val remaining = syncRemainingMs(now)
        if (remaining <= 0L) return "已到期"
        val days = (remaining + DAY_MS - 1) / DAY_MS
        return "$days 天后同步"
    }

    /** 远端曲库版本（官方 Muse）；失败返回 null。 */
    fun fetchRemoteVersion(): String? = MuseDbSync.fetchVersion()

    fun getLocalDbVersion(): String? = runCatching {
        versionFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 下载曲库：仅官方 Muse 同步源；成功后记录同步时间(重置 30 天时效)。 */
    fun download(onProgress: (Int) -> Unit): Result<File> {
        return runCatching {
            val file = MuseDbSync.download(onProgress)
            // MuseDbSync 内部已保存版本文件；这里记录同步时间作为时效起点。
            markSynced()
            MiliLog.info("DB-SYNC download OK: ${file.absolutePath} size=${formatSize(file.length())}")
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
            val free = target.parentFile?.usableSpace ?: 0L
            MiliLog.warn(
                "DB-SYNC 空间不足: free=${formatSize(free)} target=${target.absolutePath} " +
                    "err=${error.message}",
            )
            return IllegalStateException(
                "曲库分区空间不足（当前可用 ${formatSize(free)}），" +
                    "已清理下载临时文件，请释放空间后重试",
                error,
            )
        }
        val message = error.message?.takeIf { it.isNotBlank() } ?: "曲库下载失败"
        Log.w(TAG, "Database download failed: $message")
        MiliLog.warn("DB-SYNC download failed: ${error.javaClass.simpleName}: $message")
        return IllegalStateException(message, error)
    }
}
