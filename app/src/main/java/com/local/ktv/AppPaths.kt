package com.local.ktv

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 麦动 KTV 的存储命名空间。
 *
 * ⚠️ **历史实现把根目录写死成 `/storage/emulated/0/MaidongKTV`**，在部分定制 TV 盒子
 * （极米 H1S / GMUI2.0 / Android 5.1.1 = API 22）上该路径不可写：`ensureDirectories()`
 * 里 `check(it.exists() || it.mkdirs())` 失败抛异常，**App 一打开就闪退**。
 *
 * 现在改为**运行时解析**：按「设备真实外部存储根 → 历史硬编码路径 → App 私有外部目录 →
 * 内部目录」顺序挑第一个真正**可创建且可写**的目录，保证目录一定建得出来，
 * 曲库/下载/状态不因存储差异而不可用。
 *
 * 调用约定：`Application.attachBaseContext` 里**最先**调用 [init]，早于任何 AppPaths 访问。
 */
object AppPaths {
    private const val TAG = "AppPaths"

    /** 历史路径（作为候选之一保留，并与老版本数据保持兼容）。 */
    const val ROOT_PATH = "/storage/emulated/0/MaidongKTV"

    @Volatile private var baseDir: File = File(ROOT_PATH)

    /** 命中的候选标识（诊断用）。 */
    @Volatile private var resolvedBy: String = "default(not-initialized)"

    /** 全部候选的探测结果（诊断用）。 */
    @Volatile private var probeResult: String = ""

    val root: File get() = baseDir
    val stateFile: File get() = File(root, "state.json")
    val songsDir: File get() = File(root, "songs")
    val importDir: File get() = File(root, "import")
    val catalogFile: File get() = File(root, "catalog.json")
    val recordsDir: File get() = File(root, "records")
    val moviesDir: File get() = File(root, "movies")
    val appsDir: File get() = File(root, "apps")
    val backupDir: File get() = File(root, "backup")
    val databaseDir: File get() = File(root, "database")
    val databaseFile: File get() = File(databaseDir, "muse.db")
    val videoDir: File get() = File(root, "video")
    val cloudSongsDir: File get() = File(videoDir, "cloud-song")

    /** 实际使用的根目录与候选探测结果（单行，便于远程日志检索）。 */
    fun storageFacts(context: Context): String {
        val env = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
        val envState = runCatching { Environment.getExternalStorageState() }.getOrNull()
        val extFiles = runCatching { context.getExternalFilesDir(null)?.absolutePath }.getOrNull()
        val files = runCatching { context.filesDir?.absolutePath }.getOrNull()
        return buildString {
            append("root=").append(baseDir.absolutePath)
            append(" by=").append(resolvedBy)
            append(" writable=").append(runCatching { baseDir.canWrite() }.getOrDefault(false))
            append(" env=").append(env)
            append(" envState=").append(envState)
            append(" extFiles=").append(extFiles)
            append(" files=").append(files)
            append(" probe=[").append(probeResult).append(']')
        }
    }

    /**
     * 解析出一个**真正可写**的根目录。可重复调用（幂等）。
     * 放在 `Application.attachBaseContext` 最前面，保证后续任何 AppPaths 访问都拿到正确值。
     */
    fun init(context: Context) {
        if (resolvedBy != "default(not-initialized)") return
        val app = runCatching { context.applicationContext }.getOrDefault(context)

        val candidates = LinkedHashMap<String, File>()
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let {
            candidates["env(${it.absolutePath})"] = File(it, "MaidongKTV")
        }
        candidates["legacy($ROOT_PATH)"] = File(ROOT_PATH)
        runCatching { app.getExternalFilesDir(null) ?: app.filesDir }.getOrNull()?.let {
            candidates["private(${it.absolutePath})"] = File(it, "MaidongKTV")
        }
        runCatching { app.filesDir }.getOrNull()?.let {
            candidates["internal(${it.absolutePath})"] = File(it, "MaidongKTV")
        }

        val envState = runCatching { Environment.getExternalStorageState() }.getOrNull() ?: "?"
        val report = StringBuilder()
        candidates.forEach { (tag, dir) ->
            val ok = runCatching { (dir.isDirectory || dir.mkdirs()) && dir.canWrite() }
                .getOrDefault(false)
            if (report.isNotEmpty()) report.append("; ")
            report.append(tag).append('=').append(if (ok) "ok" else "fail")
            if (ok && resolvedBy == "default(not-initialized)") {
                baseDir = dir
                resolvedBy = tag
            }
        }
        probeResult = report.toString()
        Log.i(TAG, "storage root = ${baseDir.absolutePath} by=$resolvedBy envState=$envState")
        Log.i(TAG, "storage probe  = $probeResult")
    }

    /** 建目录。**永不抛异常**：存储不可用时只返回 false 并记录，由上层给出明确提示，而不是闪退。 */
    fun ensureDirectories(): Boolean {
        var allOk = true
        listOf(root, songsDir, importDir, recordsDir, moviesDir, appsDir, backupDir,
            databaseDir, videoDir, cloudSongsDir).forEach { dir ->
            val ok = runCatching { dir.exists() || dir.mkdirs() }.getOrDefault(false)
            if (!ok) {
                allOk = false
                Log.w(TAG, "cannot create directory: ${dir.absolutePath}")
            }
        }
        return allOk
    }

    /** The original IJK binary creates this log folder internally; remove it after native startup. */
    fun removeNativeLegacyLogDirectory() {
        val env = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
            ?: "/storage/emulated/0"
        runCatching { File("$env/muse").deleteRecursively() }
    }

    private fun size(text: Long): String =
        String.format(java.util.Locale.ROOT, "%.2fGB", text / 1024.0 / 1024.0 / 1024.0)

    /**
     * 真实写入探针：写 [sizeMB] 兆再删掉，验证「**现在真的能写**」。
     *
     * `usableSpace` 只反映**块空间**；inode 耗尽、配额、FUSE/ROM 限制都会返回 ENOSPC
     * 但 still 报着几 GB 可用 —— 所以判断"是不是空间问题"必须靠真实写入，而不是看数字。
     */
    fun probeWrite(dir: File, sizeMB: Int = 8): String = runCatching {
        val probe = File(dir, ".write_probe.tmp")
        try {
            val buffer = ByteArray(1024 * 1024)
            FileOutputStream(probe).use { out ->
                repeat(sizeMB) { out.write(buffer) }
                out.flush()
                out.fd.sync()
            }
            "ok(${sizeMB}MB)"
        } finally {
            runCatching { probe.delete() }
        }
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }

    private fun mountLines(): String = runCatching {
        File("/proc/mounts").readLines()
            .filter { line ->
                line.contains("sdcard") || line.contains("/data ") ||
                    line.contains("/storage") || line.contains("/mnt")
            }
            .take(12)
            .joinToString(" | ") { it.substringBefore(" ").trim() }
    }.getOrNull() ?: "(unreadable)"

    /** 一次性诊断串：空间、真实可写性、挂载点。失败时上报远程日志并显示在界面上。 */
    fun diagnose(): String {
        val dir = databaseDir
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)
        val total = runCatching { dir.totalSpace }.getOrDefault(0L)
        val envState = runCatching { Environment.getExternalStorageState() }.getOrNull()
        return "root=${root.absolutePath} free=${size(free)} total=${size(total)} " +
            "envState=$envState probe=${probeWrite(dir)} mounts=[${mountLines()}]"
    }
}
