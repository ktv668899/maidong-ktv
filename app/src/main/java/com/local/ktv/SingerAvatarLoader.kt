package com.local.ktv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Singer-image loader following the original app's Glide behavior: recycled-view protection,
 * memory and disk cache, request coalescing and sampled decoding instead of full-size bitmaps.
 */
object SingerAvatarLoader {
    private const val MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val waiting = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>>()

    fun load(view: ImageView, imageUrl: String?, placeholder: Int) {
        val key = imageUrl.orEmpty()
        view.tag = key
        view.setImageResource(placeholder)
        if (key.isEmpty()) return
        memory.get(key)?.let { bitmap ->
            view.setImageBitmap(bitmap)
            return
        }

        val waiters = waiting.computeIfAbsent(key) { CopyOnWriteArrayList() }
        waiters += WeakReference(view)
        if (waiters.size > 1) return
        val cacheDir = File(view.context.applicationContext.cacheDir, "singer_avatars").apply { mkdirs() }
        executor.execute {
            val bitmap = runCatching { decode(key, cacheDir) }.getOrNull()
            if (bitmap != null) memory.put(key, bitmap)
            val targets = waiting.remove(key).orEmpty()
            main.post {
                if (bitmap != null) targets.forEach { reference ->
                    reference.get()?.takeIf { it.tag == key }?.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decode(value: String, cacheDir: File): Bitmap? {
        if (value.startsWith("/")) return decodeSampled(File(value))
        val cacheFile = File(cacheDir, sha256(value) + ".img")
        if (cacheFile.isFile && cacheFile.length() > 0) {
            decodeSampled(cacheFile)?.let { return it }
            cacheFile.delete()
        }
        val temporary = File(cacheDir, cacheFile.name + ".tmp")
        val connection = URL(value).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MaidongKTV/${BuildConfig.VERSION_NAME}")
            require(connection.responseCode in 200..299)
            require(connection.contentLengthLong <= 0 || connection.contentLengthLong <= MAX_DOWNLOAD_BYTES)
            var total = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_DOWNLOAD_BYTES)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!temporary.renameTo(cacheFile)) {
                temporary.copyTo(cacheFile, overwrite = true)
                temporary.delete()
            }
            return decodeSampled(cacheFile)
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
