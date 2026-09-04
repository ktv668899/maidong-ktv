package com.local.ktv

import org.json.JSONArray
import java.io.File
import java.util.Locale

class SongLibrary {
    @JvmField val rootDir = AppPaths.songsDir
    @JvmField val importDir = AppPaths.importDir
    private val indexFile = AppPaths.catalogFile
    private val localSongs = mutableListOf<Song>()
    private val remoteSongs = mutableListOf<Song>()
    @JvmField val muse = MuseDatabase()

    fun ensureDirs() {
        rootDir.mkdirs()
        importDir.mkdirs()
        indexFile.parentFile?.mkdirs()
    }

    fun catalogFile(): File = indexFile

    fun scanLocal(): List<Song> {
        ensureDirs()
        localSongs.clear()
        scanDir(rootDir)
        scanDir(importDir)
        File("/storage").listFiles().orEmpty()
            .filterNot { it.name == "emulated" || it.name == "self" }
            .forEach { root ->
                listOf(File(root, "KTV"), File(root, "songs"))
                    .filter(File::exists)
                    .forEach(::scanDir)
            }
        localSongs.sortBy { it.title.orEmpty().lowercase(Locale.ROOT) }
        return localSongs.toList()
    }

    fun loadCachedRemote(): List<Song> {
        remoteSongs.clear()
        runCatching {
            if (indexFile.exists()) {
                val array = JSONArray(indexFile.readText())
                repeat(array.length()) { remoteSongs += Song.remote(array.getJSONObject(it)) }
            }
        }
        return remoteSongs.toList()
    }

    fun saveRemote(array: JSONArray) {
        ensureDirs()
        indexFile.writeText(array.toString(2))
        loadCachedRemote()
    }

    fun clearRemoteCache(): Boolean {
        remoteSongs.clear()
        return indexFile.exists() && indexFile.delete()
    }

    fun allSongs(): List<Song> = localSongs + remoteSongs

    fun categories(): List<String> = linkedSetOf("全部").apply {
        allSongs().forEach { add(it.category?.takeIf(String::isNotEmpty) ?: "其他") }
    }.toList()

    fun languages(): List<String> = linkedSetOf("全部").apply {
        allSongs().forEach { add(it.language?.takeIf(String::isNotEmpty) ?: "其他") }
    }.toList()

    fun singers(): List<String> = linkedSetOf<String>().apply {
        allSongs().forEach { add(it.singer?.takeIf(String::isNotEmpty) ?: "未知歌手") }
    }.toList()

    fun targetFor(song: Song): File {
        val clean = "${song.singer}-${song.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val raw = song.downloadUrl.orEmpty()
        val dot = raw.lastIndexOf('.')
        val query = raw.indexOf('?', dot.coerceAtLeast(0))
        val candidate = if (dot >= 0) raw.substring(dot, if (query > dot) query else raw.length) else ".mp4"
        val extension = candidate.takeIf { it.length <= 8 && '/' !in it } ?: ".mp4"
        return File(rootDir, clean + extension)
    }

    private fun scanDir(dir: File) {
        dir.listFiles().orEmpty().forEach { file ->
            when {
                file.isDirectory -> scanDir(file)
                isMedia(file.name) -> localSongs += Song.local(file.absolutePath, file.name)
            }
        }
    }

    private fun isMedia(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return listOf(".mp4", ".mkv", ".avi", ".mp3", ".flac", ".wav").any(lower::endsWith)
    }
}
