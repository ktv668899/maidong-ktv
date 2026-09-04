package com.local.ktv

import android.util.Log
import com.liulishuo.okdownload.DownloadTask as OkDownloadTask
import com.liulishuo.okdownload.OkDownload
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.listener.DownloadListener2
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object SongOkDownloadManager {
    private const val TAG = "SongDownload"
    const val MIN_VALID_FILE_SIZE = SongFileValidator.MIN_VALID_FILE_SIZE
    private const val MAX_RETRY_COUNT = 3
    private const val STALL_TIMEOUT_MS = 25_000L

    interface DownloadCallback {
        fun onDownloadStart(song: Song)
        fun onDownloadProgress(song: Song, progress: Int)
        fun onDownloadReadyToPlay(song: Song)
        fun onDownloadComplete(song: Song, localPath: String)
        fun onDownloadFailed(song: Song, error: String)
    }

    private val tasks = ConcurrentHashMap<String, OkDownloadTask>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val callbacks = ConcurrentHashMap<String, DownloadCallback>()
    private val progress = ConcurrentHashMap<String, Int>()
    private val totalLengths = ConcurrentHashMap<String, Long>()
    private val forcedRetryReasons = ConcurrentHashMap<String, String>()
    private val manuallyCanceled = ConcurrentHashMap.newKeySet<String>()
    private val io = Executors.newFixedThreadPool(4)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    @JvmStatic
    fun getLocalFile(song: Song): File {
        val fileName = song.filename?.takeIf(String::isNotEmpty) ?: "${song.id}.ts"
        return File("${MuseDatabase.VIDEO_ROOT}/${MuseDatabase.CLOUD_SONG_DIR}", fileName)
    }

    @JvmStatic
    fun isDownloaded(song: Song): Boolean {
        val file = getLocalFile(song)
        if (file.exists()) {
            val inspection = SongFileValidator.inspect(file, SongFileValidator.requiresTransportStream(file))
            if (!inspection.valid) {
                Log.w(TAG, "Discarding invalid song file: ${file.absolutePath}, ${inspection.reason}")
                SongFileValidator.forget(file)
                file.delete()
                song.path = null
            }
        }
        val downloaded = file.exists()
        if (downloaded) {
            // Catalog rows may still contain their original cloud-songNNNN path.
            // Once the unified app directory has a verified file, it is the only
            // valid playback source regardless of the stale non-null catalog path.
            song.path = file.absolutePath
        }
        return downloaded
    }

    @JvmStatic
    fun isDownloading(song: Song): Boolean {
        val key = songKey(song)
        return tasks.containsKey(key) || pending.contains(key)
    }

    @JvmStatic
    fun download(song: Song, callback: DownloadCallback?) {
        if (isDownloaded(song)) {
            val localFile = getLocalFile(song)
            Log.i(TAG, "Using verified local file: ${localFile.absolutePath}, ${localFile.length()} bytes")
            callback?.onDownloadComplete(song, localFile.absolutePath)
            return
        }
        val key = songKey(song)
        if (tasks.containsKey(key) || !pending.add(key)) {
            callback?.onDownloadStart(song)
            return
        }
        io.execute {
            try {
                var url = buildDownloadUrl(song)
                if (url.isEmpty()) {
                    // 首次下载偶发解析不到地址(JS 桥冷启动/节点瞬时抖动)。短暂退避后重试一次，
                    // 复现“手动重试即恢复”的体验，避免第一首歌一次性报失败。
                    Log.w(TAG, "First URL resolve failed for ${song.title}, retrying once")
                    Thread.sleep(1_200)
                    url = buildDownloadUrl(song, forceRefresh = true)
                }
                if (url.isEmpty()) {
                    pending.remove(key)
                    callback?.onDownloadFailed(song, "无法生成下载地址")
                } else {
                    startDownloadTask(song, url, callback, 0)
                }
            } catch (error: Throwable) {
                pending.remove(key)
                callback?.onDownloadFailed(song, error.message ?: "下载初始化失败")
            }
        }
    }

    @JvmStatic
    fun cancelDownload(song: Song) {
        val key = songKey(song)
        pending.remove(key)
        tasks.remove(key)?.let { task ->
            manuallyCanceled.add(key)
            task.cancel()
            clearBrokenDownload(task, getLocalFile(song))
        }
        callbacks.remove(key)
        progress.remove(key)
        totalLengths.remove(key)
    }

    @JvmStatic
    fun getDownloadProgress(song: Song): Int = progress[songKey(song)] ?: 0

    private fun buildDownloadUrl(song: Song, forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            song.downloadUrl?.takeIf(String::isNotBlank)?.let { cached ->
                if (!AdVideoFilter.isAdVideo(cached)) return cached
                // 缓存的下载地址是广告视频,清掉并重新解析(广告会被桥接层屏蔽/替换)。
                Log.w(TAG, "丢弃广告缓存地址: ${song.title}")
                song.downloadUrl = null
            }
        }
        val musicNo = song.filename?.removeSuffix(".ts")?.removeSuffix(".ls") ?: song.id
        return SongApiClient.getSongDownloadUrl(musicNo).orEmpty().also { url ->
            // 请求源 = 本次点歌实际命中的源(JS 上报); 取不到时留空。
            val srcHost = SongApiClient.lastSongSourceHost()
            if (url.isNotEmpty()) {
                song.downloadUrl = url
                val cdnHost = runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"
                MiliLog.info(
                    "点歌编号=$musicNo 标题=${song.title} 请求源=$srcHost 状态=成功 urlHost=$cdnHost",
                )
            } else {
                Log.w(TAG, "Unable to get download URL: ${song.title}")
                MiliLog.error(
                    "点歌编号=$musicNo 标题=${song.title} 请求源=$srcHost 状态=失败 详情=无法生成下载地址",
                )
            }
        }
    }

    private fun startDownloadTask(song: Song, url: String, callback: DownloadCallback?, attempt: Int) {
        val key = songKey(song)
        val target = getLocalFile(song)
        val partial = File(target.parentFile, "${target.name}.download")
        target.parentFile?.mkdirs()
        if (attempt > 0) partial.delete()
        val task = OkDownloadTask.Builder(url, partial.parentFile!!)
            .setFilename(partial.name)
            .setPassIfAlreadyCompleted(false)
            .setMinIntervalMillisCallbackProcess(250)
            .build()
        tasks[key] = task
        pending.remove(key)
        callback?.let { callbacks[key] = it }
        progress[key] = 0
        totalLengths[key] = 0
        val downloaded = AtomicLong(0)
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        var httpResponseCode = 0

        scheduleStallCheck(song, task, lastActivityAt, attempt)
        task.enqueue(object : DownloadListener2() {
            override fun taskStart(task: OkDownloadTask) {
                lastActivityAt.set(System.currentTimeMillis())
                callbacks[key]?.onDownloadStart(song)
            }

            override fun connectEnd(
                task: OkDownloadTask,
                blockCount: Int,
                responseCode: Int,
                responseHeaderFields: MutableMap<String, MutableList<String>>,
            ) {
                httpResponseCode = responseCode
                lastActivityAt.set(System.currentTimeMillis())
            }

            override fun downloadFromBeginning(task: OkDownloadTask, info: BreakpointInfo, cause: ResumeFailedCause) {
                totalLengths[key] = info.totalLength
            }

            override fun downloadFromBreakpoint(task: OkDownloadTask, info: BreakpointInfo) {
                totalLengths[key] = info.totalLength
                downloaded.set(info.totalOffset)
            }

            override fun fetchProgress(task: OkDownloadTask, blockIndex: Int, increaseBytes: Long) {
                if (increaseBytes > 0) lastActivityAt.set(System.currentTimeMillis())
                val total = totalLengths[key] ?: return
                if (total <= 0) return
                val value = (downloaded.addAndGet(increaseBytes) * 100 / total).toInt().coerceIn(0, 98)
                progress[key] = value
                callbacks[key]?.onDownloadProgress(song, value)
            }

            override fun taskEnd(task: OkDownloadTask, cause: EndCause, realCause: Exception?) {
                tasks.remove(key, task)
                totalLengths.remove(key)
                val cb = callbacks.remove(key)
                val forcedReason = forcedRetryReasons.remove(key)
                if (manuallyCanceled.remove(key)) return
                if (cause != EndCause.COMPLETED) {
                    val retryable = forcedReason != null || cause != EndCause.CANCELED
                    if (retryable && attempt < MAX_RETRY_COUNT) {
                        clearBrokenDownload(task, target)
                        val response = if (httpResponseCode > 0) " HTTP $httpResponseCode" else ""
                        retry(song, cb, attempt + 1, forcedReason ?: "${cause.name}$response")
                    } else {
                        pending.remove(key)
                        progress.remove(key)
                        cb?.onDownloadFailed(song, cause.name + (realCause?.message?.let { ": $it" } ?: ""))
                    }
                    return
                }
                pending.add(key)
                progress[key] = 99
                cb?.onDownloadProgress(song, 99)
                io.execute { finalizeDownload(song, task, target, partial, cb, attempt) }
            }
        })
    }

    private fun finalizeDownload(
        song: Song,
        task: OkDownloadTask,
        target: File,
        partial: File,
        callback: DownloadCallback?,
        attempt: Int,
    ) {
        val key = songKey(song)
        try {
            var downloadedFile = task.file ?: partial
            if (!downloadedFile.exists() || downloadedFile.length() < MIN_VALID_FILE_SIZE) {
                val size = downloadedFile.takeIf(File::exists)?.length() ?: 0L
                clearBrokenDownload(task, target)
                retryOrFail(song, callback, attempt, "file too small: $size", "下载文件异常，已自动清理")
                return
            }
            if (TsDecryptor.isEncrypted(downloadedFile)) {
                val decrypted = File(downloadedFile.parentFile, "${downloadedFile.name}.decrypted")
                decrypted.delete()
                val decryptedOk = TsDecryptor.decryptFile(downloadedFile, decrypted)
                val replaced = decryptedOk && downloadedFile.delete() && decrypted.renameTo(downloadedFile)
                if (!replaced) {
                    clearBrokenDownload(task, target)
                    retryOrFail(song, callback, attempt, "decrypt failed", "歌曲解密失败，异常文件已删除")
                    return
                }
                downloadedFile = task.file ?: partial
            }
            val inspection = SongFileValidator.inspect(
                downloadedFile,
                SongFileValidator.requiresTransportStream(target),
            )
            if (!inspection.valid) {
                Log.w(TAG, "Rejecting downloaded media for ${song.title}: ${inspection.reason}")
                SongFileValidator.forget(downloadedFile)
                clearBrokenDownload(task, target)
                pending.remove(key)
                progress.remove(key)
                val message = inspection.durationMs?.let { duration ->
                    "服务器返回试听片段（${duration / 1000}秒），未保存也未加入已点"
                } ?: "下载文件不是有效歌曲视频，未保存也未加入已点"
                callback?.onDownloadFailed(song, message)
                return
            }
            if (target.exists() && !target.delete()) {
                pending.remove(key)
                progress.remove(key)
                callback?.onDownloadFailed(song, "无法替换旧的歌曲文件")
                return
            }
            if (!downloadedFile.renameTo(target)) {
                clearBrokenDownload(task, target)
                retryOrFail(song, callback, attempt, "rename failed", "下载文件落盘失败")
                return
            }
            pending.remove(key)
            progress.remove(key)
            song.path = target.absolutePath
            callback?.onDownloadProgress(song, 100)
            callback?.onDownloadComplete(song, target.absolutePath)
        } catch (error: Throwable) {
            Log.e(TAG, "Finalizing download failed: ${song.title}", error)
            clearBrokenDownload(task, target)
            retryOrFail(song, callback, attempt, error.message ?: "finalize failed", "下载文件处理失败")
        }
    }

    private fun retryOrFail(
        song: Song,
        callback: DownloadCallback?,
        attempt: Int,
        reason: String,
        terminalMessage: String,
    ) {
        if (attempt < MAX_RETRY_COUNT) {
            retry(song, callback, attempt + 1, reason)
        } else {
            val key = songKey(song)
            pending.remove(key)
            progress.remove(key)
            callback?.onDownloadFailed(song, terminalMessage)
        }
    }

    private fun scheduleStallCheck(
        song: Song,
        task: OkDownloadTask,
        lastActivityAt: AtomicLong,
        attempt: Int,
    ) {
        watchdog.schedule({
            val key = songKey(song)
            if (tasks[key] !== task) return@schedule
            val idleMs = System.currentTimeMillis() - lastActivityAt.get()
            if (idleMs >= STALL_TIMEOUT_MS) {
                Log.w(TAG, "Download stalled for ${idleMs}ms: ${song.title}, attempt=$attempt")
                forcedRetryReasons[key] = "stalled for ${idleMs}ms"
                task.cancel()
            } else {
                scheduleStallCheck(song, task, lastActivityAt, attempt)
            }
        }, STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun clearBrokenDownload(task: OkDownloadTask, target: File) {
        runCatching { OkDownload.with().breakpointStore().remove(task.id) }
        val partial = task.file ?: File(target.parentFile, "${target.name}.download")
        if (partial.absolutePath != target.absolutePath) partial.delete()
        File(partial.parentFile, "${partial.name}.decrypted").delete()
    }

    private fun retry(song: Song, callback: DownloadCallback?, attempt: Int, reason: String) {
        Log.w(TAG, "Retrying ${song.title}, attempt=$attempt, reason=$reason")
        val key = songKey(song)
        pending.add(key)
        progress[key] = 0
        callback?.onDownloadProgress(song, 0)
        song.downloadUrl = null
        SongApiClient.clearTokenCache()
        io.execute {
            Thread.sleep((attempt * 750L).coerceAtMost(2_250L))
            val url = runCatching { buildDownloadUrl(song, forceRefresh = true) }.getOrDefault("")
            if (url.isEmpty()) {
                if (attempt < MAX_RETRY_COUNT) {
                    retry(song, callback, attempt + 1, "refresh download URL failed")
                } else {
                    pending.remove(key)
                    progress.remove(key)
                    callback?.onDownloadFailed(song, "刷新下载地址失败")
                }
            } else {
                startDownloadTask(song, url, callback, attempt)
            }
        }
    }

    private fun songKey(song: Song): String = song.id?.takeIf(String::isNotEmpty)
        ?: song.filename?.takeIf(String::isNotEmpty)
        ?: song.title.orEmpty()

}
