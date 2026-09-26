package com.local.ktv

import android.util.Log
import com.liulishuo.okdownload.DownloadTask as OkDownloadTask
import com.liulishuo.okdownload.OkDownload
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.listener.DownloadListener2
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object SongOkDownloadManager {
    private const val TAG = "SongDownload"
    const val MIN_VALID_FILE_SIZE = SongFileValidator.MIN_VALID_FILE_SIZE
    /** 网络恢复后最多自动重试几首（防止一次拉起过多并发下载）。 */
    private const val MAX_NETWORK_RETRY = 8
    private const val MAX_RETRY_COUNT = 3
    private const val STALL_TIMEOUT_MS = 25_000L

    /** 曲库视频目录下会被当作"已下载歌曲"的文件后缀（用于一次性清理历史广告缓存）。 */
    private val MEDIA_EXTENSIONS = setOf("ts", "ls", "mp4", "mkv")

    interface DownloadCallback {
        fun onDownloadStart(song: Song)
        fun onDownloadProgress(song: Song, progress: Int)
        fun onDownloadReadyToPlay(song: Song)
        fun onDownloadComplete(song: Song, localPath: String)
        fun onDownloadFailed(song: Song, error: String)
    }

    private val tasks = ConcurrentHashMap<String, OkDownloadTask>()
    // ConcurrentHashMap.newKeySet() 是 API 24+（低版本 NoSuchMethodError）；
    // Collections.newSetFromMap 自 API 9 起可用，且返回普通 Set，add/remove/contains 均成立于 API 1。
    private val pending: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val callbacks = ConcurrentHashMap<String, DownloadCallback>()
    private val progress = ConcurrentHashMap<String, Int>()
    private val totalLengths = ConcurrentHashMap<String, Long>()
    private val forcedRetryReasons = ConcurrentHashMap<String, String>()
    private val manuallyCanceled: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
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

    /**
     * 一次性清理历史遗留的歌曲缓存（广告漏放期间的产物）。
     *
     * 背景（2026-09-26 事故）：1.4.9 及以前，JS 取址返回的地址在 `buildDownloadUrl` 的成功分支里
     * **完全没有过广告校验**，广告视频（如 `gz.ac16.vip`）会被当成歌曲下载并落盘；它又能通过
     * [SongFileValidator] 的内容校验（≥6MB / ≥60s / TS 包完整），因此**靠内容无法识别**，
     * 而且 [isDownloaded] 会直接复用该文件、连取址都不会再发生 —— 广告会一直播下去。
     *
     * 这类遗留文件只能在升级时清一次；此后由「取址即校验」（
     * [AdVideoFilter.isTrustedSongUrl]）保证不再产生。清掉只是让歌曲重新下载，不影响任何用户数据。
     *
     * @return 删除的文件数（已清理过则返回 0）
     */
    @JvmStatic
    fun purgeLegacySongCacheOnce(context: android.content.Context): Int {
        val marker = File(context.filesDir, "song_cache_purged_v1")
        if (marker.exists()) return 0
        var removed = 0
        runCatching {
            val dir = File("${MuseDatabase.VIDEO_ROOT}/${MuseDatabase.CLOUD_SONG_DIR}")
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.extension.lowercase() in MEDIA_EXTENSIONS) {
                    SongFileValidator.forget(file)
                    if (file.delete()) removed++
                }
            }
            marker.writeText("purged=${System.currentTimeMillis()} removed=$removed", Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "purge legacy song cache failed: ${it.message}") }
        // 首次启动才会真正有动作，之后 marker 存在会直接返回 0，不产生噪声日志。
        if (removed > 0 || !marker.exists()) {
            MiliLog.warn("SONG-AD 一次性清理历史歌曲缓存 removed=$removed（旧版本可能把广告缓存成歌曲）")
        }
        return removed
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
                    // 取不到地址通常也是网络/源站不可达, 记下来供网络恢复后自动重试。
                    if (isNetworkFailure(null, 0)) networkFailed[key] = song
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
            networkFailed.remove(key)
            task.cancel()
            clearBrokenDownload(task, getLocalFile(song))
        }
        callbacks.remove(key)
        progress.remove(key)
        totalLengths.remove(key)
    }

    @JvmStatic
    fun getDownloadProgress(song: Song): Int = progress[songKey(song)] ?: 0

    /**
     * 网络恢复后自动重试「因网络原因失败」的下载（最多 [MAX_NETWORK_RETRY] 首）。
     *
     * 断网期间的失败大多是连不上/超时/HTTP 0，属于可恢复；恢复后重试一次，避免用户
     * 手动重新点歌。手动取消的歌不会进入这个集合。
     *
     * @return 实际发起重试的数量
     */
    @JvmStatic
    fun retryNetworkFailed(): Int {
        val retry = synchronized(networkFailed) {
            networkFailed.values.toList().also { networkFailed.clear() }
        }
        if (retry.isEmpty()) return 0
        val limited = retry.take(MAX_NETWORK_RETRY)
        Log.i(TAG, "network recovered, retrying ${limited.size} failed download(s)")
        io.execute {
            limited.forEach { song -> runCatching { download(song, callbacks[songKey(song)]) } }
        }
        return limited.size
    }

    /** 判断一个失败是否属于「网络原因」（断网/超时/连不上/HTTP 0）。 */
    private fun isNetworkFailure(realCause: Exception?, httpCode: Int): Boolean {
        if (!KtvNetworkMonitor.online) return true
        if (httpCode == 0) return true
        val cause = realCause ?: return false
        return cause is java.net.UnknownHostException ||
            cause is java.net.ConnectException ||
            cause is java.net.SocketTimeoutException ||
            cause is java.net.NoRouteToHostException ||
            cause is java.net.SocketException ||
            cause is javax.net.ssl.SSLException ||
            cause is java.io.EOFException ||
            cause is java.io.InterruptedIOException
    }

    private fun buildDownloadUrl(song: Song, forceRefresh: Boolean = false): String {
        val musicNo = song.filename?.removeSuffix(".ts")?.removeSuffix(".ls") ?: song.id
        if (!forceRefresh) {
            song.downloadUrl?.takeIf(String::isNotBlank)?.let { cached ->
                if (AdVideoFilter.isTrustedSongUrl(cached, musicNo)) return cached
                // 缓存的地址不可信（广告 / 文件名与点歌编号不符）：除了清地址，还要**删掉已落盘的
                // 同名文件** —— 广告视频能通过内容校验（≥6MB / ≥60s / TS 包完整），
                // 一旦被缓存成歌曲，isDownloaded() 会直接复用、连取址都不会再发生。
                Log.w(TAG, "丢弃不可信缓存地址: ${song.title} -> $cached")
                MiliLog.warn("SONG-AD 丢弃不可信缓存地址 编号=$musicNo url=$cached")
                song.downloadUrl = null
                purgeCachedSongFile(song, "untrusted-cached-url")
            }
        }
        return SongApiClient.getSongDownloadUrl(musicNo).orEmpty().also { url ->
            // 请求源 = 本次点歌实际命中的源(JS 上报); 取不到时留空。
            val srcHost = SongApiClient.lastSongSourceHost()
            // ⚠️ 广告漏放的**最终兜底防线**：不管地址来自 JS 取址、原生兜底还是 DB 回退，
            // 都必须过 AdVideoFilter.isTrustedSongUrl（2026-09-26 事故：JS 路径返回
            // gz.ac16.vip 的广告视频，因为这里的成功分支完全没做广告校验而被当成歌曲下载播放）。
            if (url.isNotEmpty() && AdVideoFilter.isTrustedSongUrl(url, musicNo)) {
                song.downloadUrl = url
                val cdnHost = runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"
                MiliLog.info(
                    "点歌编号=$musicNo 标题=${song.title} 请求源=$srcHost 状态=成功 urlHost=$cdnHost",
                )
            } else if (url.isNotEmpty()) {
                Log.w(TAG, "Untrusted URL rejected for ${song.title}: $url")
                MiliLog.error(
                    "点歌编号=$musicNo 标题=${song.title} 请求源=$srcHost 状态=失败 " +
                        "详情=地址不可信(广告或文件名不符) url=$url",
                )
                purgeCachedSongFile(song, "untrusted-resolved-url")
                ""
            } else {
                Log.w(TAG, "Unable to get download URL: ${song.title}")
                MiliLog.error(
                    "点歌编号=$musicNo 标题=${song.title} 请求源=$srcHost 状态=失败 详情=无法生成下载地址",
                )
                ""
            }
        }
    }

    /** 删除某首歌已落盘的文件（发现地址不可信/命中广告时调用，让下次点歌重新取址下载）。 */
    private fun purgeCachedSongFile(song: Song, reason: String) {
        runCatching {
            val file = getLocalFile(song)
            if (file.exists()) {
                val sizeMb = file.length() / 1024 / 1024
                SongFileValidator.forget(file)
                val deleted = file.delete()
                MiliLog.warn(
                    "SONG-AD 清除可疑歌曲缓存 编号=${song.id} 原因=$reason " +
                        "大小=${sizeMb}MB 已删除=$deleted",
                )
            }
            song.path = null
        }.onFailure { Log.w(TAG, "purge cached file failed: ${it.message}") }
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
                        if (isNetworkFailure(realCause, httpResponseCode)) {
                            networkFailed[key] = song
                            Log.w(TAG, "download failed by network: ${song.title} (${cause.name})")
                        }
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
            networkFailed.remove(key)
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

    /** 因网络原因失败的歌曲（网络恢复后自动重试一次）。 */
    private val networkFailed = Collections.synchronizedMap(LinkedHashMap<String, Song>())

    private fun songKey(song: Song): String = song.id?.takeIf(String::isNotEmpty)
        ?: song.filename?.takeIf(String::isNotEmpty)
        ?: song.title.orEmpty()

}
