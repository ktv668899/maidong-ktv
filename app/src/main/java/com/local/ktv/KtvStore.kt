package com.local.ktv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.LinkedHashSet

class KtvStore {
    private val file = AppPaths.stateFile

    fun stateFile(): File = file

    @JvmField var loopOne = false
    @JvmField var autoNext = true
    @JvmField var originalVocal = true
    @JvmField var vocalChannelMode = "自动"
    @JvmField var musicVolume = 70
    @JvmField var micVolume = 70
    @JvmField var tone = 0
    @JvmField var atmosphere = "标准"
    @JvmField var singMode = "普通演唱"
    @JvmField var recordEnabled = true
    @JvmField var scoreEnabled = true
    @JvmField var lastScore = 0
    @JvmField var lastScoreSong = ""
    @JvmField var marqueeEnabled = true
    @JvmField var marqueeText = "欢迎使用麦动"
    @JvmField var playWhileDownloading = false
    @JvmField var clearDownloadsOnBoot = false
    @JvmField var autoFullscreenSeconds = 30
    @JvmField var showUsbSongs = true
    @JvmField var autoDeleteSongs = true
    @JvmField var reserveStorageGb = 1.0
    @JvmField var floatingButtonEnabled = true
    @JvmField var songTitleSubtitleEnabled = true
    @JvmField var orderedSongOriginal = true
    @JvmField var orderedVolumeMode = 0
    @JvmField var orderedVocalMode = 0
    @JvmField var voiceEngineEnabled = true
    @JvmField var voiceEngineVolume = 70
    @JvmField var lightMode = "自动"
    @JvmField var audioDelayMs = 0
    @JvmField var screenBrightness = 80
    @JvmField var screenMode = "适应屏幕"
    @JvmField var doubleScreenEnabled = false
    @JvmField var tableBroadcastEnabled = true
    @JvmField var tableBroadcastSeconds = 8
    @JvmField var tableBroadcastText = "欢迎光临"
    @JvmField val orderQueue = mutableListOf<Song>()
    @JvmField val sangHistory = mutableListOf<Song>()
    @JvmField val scoreHistory = mutableListOf<String>()
    @JvmField val broadcastHistory = mutableListOf<String>()
    @JvmField val favoriteIds = LinkedHashSet<String>()
    @JvmField val hiddenSongIds = LinkedHashSet<String>()
    @JvmField val playlists = mutableListOf<String>()
    @JvmField val playlistSongs = LinkedHashMap<String, MutableSet<String>>()

    fun load() {
        runCatching {
            if (!file.exists()) return@runCatching
            val root = JSONObject(file.readText())
            loopOne = root.optBoolean("loopOne", false)
            autoNext = root.optBoolean("autoNext", true)
            originalVocal = root.optBoolean("originalVocal", true)
            vocalChannelMode = root.optString("vocalChannelMode", "自动")
            musicVolume = root.optInt("musicVolume", 70)
            micVolume = root.optInt("micVolume", 70)
            tone = root.optInt("tone", 0)
            atmosphere = root.optString("atmosphere", "标准")
            singMode = root.optString("singMode", "普通演唱")
            recordEnabled = root.optBoolean("recordEnabled", true)
            scoreEnabled = root.optBoolean("scoreEnabled", true)
            lastScore = root.optInt("lastScore", 0)
            lastScoreSong = root.optString("lastScoreSong", "")
            marqueeEnabled = root.optBoolean("marqueeEnabled", true)
            marqueeText = root.optString("marqueeText", "欢迎使用麦动")
            playWhileDownloading = root.optBoolean("playWhileDownloading", false)
            clearDownloadsOnBoot = root.optBoolean("clearDownloadsOnBoot", false)
            autoFullscreenSeconds = root.optInt("autoFullscreenSeconds", 30)
            showUsbSongs = root.optBoolean("showUsbSongs", true)
            autoDeleteSongs = root.optBoolean("autoDeleteSongs", true)
            reserveStorageGb = root.optDouble("reserveStorageGb", 1.0).coerceIn(0.5, 64.0)
            floatingButtonEnabled = root.optBoolean("floatingButtonEnabled", true)
            songTitleSubtitleEnabled = root.optBoolean("songTitleSubtitleEnabled", true)
            orderedSongOriginal = root.optBoolean("orderedSongOriginal", true)
            orderedVolumeMode = root.optInt("orderedVolumeMode", 0)
            orderedVocalMode = root.optInt("orderedVocalMode", 0)
            voiceEngineEnabled = root.optBoolean("voiceEngineEnabled", true)
            voiceEngineVolume = root.optInt("voiceEngineVolume", 70)
            lightMode = root.optString("lightMode", "自动")
            audioDelayMs = root.optInt("audioDelayMs", 0)
            screenBrightness = root.optInt("screenBrightness", 80)
            screenMode = root.optString("screenMode", "适应屏幕")
            doubleScreenEnabled = root.optBoolean("doubleScreenEnabled", false)
            tableBroadcastEnabled = root.optBoolean("tableBroadcastEnabled", true)
            tableBroadcastSeconds = root.optInt("tableBroadcastSeconds", 8)
            tableBroadcastText = root.optString("tableBroadcastText", "欢迎光临")
            readSongs(root.optJSONArray("orderQueue"), orderQueue)
            readSongs(root.optJSONArray("sangHistory"), sangHistory)
            root.optJSONArray("scoreHistory").copyStringsTo(scoreHistory)
            root.optJSONArray("broadcastHistory").copyStringsTo(broadcastHistory)
            root.optJSONArray("favoriteIds").copyStringsTo(favoriteIds)
            root.optJSONArray("hiddenSongIds").copyStringsTo(hiddenSongIds)
            root.optJSONArray("playlists").copyStringsTo(playlists)
            root.optJSONObject("playlistSongs")?.let { map ->
                map.names()?.let { names ->
                    repeat(names.length()) {
                        val name = names.optString(it)
                        val values = LinkedHashSet<String>()
                        map.optJSONArray(name).copyStringsTo(values)
                        playlistSongs[name] = values
                    }
                }
            }
        }
        // 默认值迁移(v1.2.5+):原唱默认 伴唱→原唱、自动全屏 0→30 秒、点播歌曲原伴唱 伴唱→原唱。
        // 仅当旧数据仍是旧默认值时升级为新默认;已在设置里手动改过的值不受影响。
        if (!originalVocal) originalVocal = true
        if (autoFullscreenSeconds == 0) autoFullscreenSeconds = 30
        if (!orderedSongOriginal) orderedSongOriginal = true
        ensureDefaultPlaylists()
    }

    fun save() {
        writeSnapshot(snapshotJson())
    }

    fun snapshotJson(): String = runCatching {
            val root = JSONObject().apply {
                put("loopOne", loopOne)
                put("autoNext", autoNext)
                put("originalVocal", originalVocal)
                put("vocalChannelMode", vocalChannelMode)
                put("musicVolume", musicVolume)
                put("micVolume", micVolume)
                put("tone", tone)
                put("atmosphere", atmosphere)
                put("singMode", singMode)
                put("recordEnabled", recordEnabled)
                put("scoreEnabled", scoreEnabled)
                put("lastScore", lastScore)
                put("lastScoreSong", lastScoreSong)
                put("marqueeEnabled", marqueeEnabled)
                put("marqueeText", marqueeText)
                put("playWhileDownloading", playWhileDownloading)
                put("clearDownloadsOnBoot", clearDownloadsOnBoot)
                put("autoFullscreenSeconds", autoFullscreenSeconds)
                put("showUsbSongs", showUsbSongs)
                put("autoDeleteSongs", autoDeleteSongs)
                put("reserveStorageGb", reserveStorageGb)
                put("floatingButtonEnabled", floatingButtonEnabled)
                put("songTitleSubtitleEnabled", songTitleSubtitleEnabled)
                put("orderedSongOriginal", orderedSongOriginal)
                put("orderedVolumeMode", orderedVolumeMode)
                put("orderedVocalMode", orderedVocalMode)
                put("voiceEngineEnabled", voiceEngineEnabled)
                put("voiceEngineVolume", voiceEngineVolume)
                put("lightMode", lightMode)
                put("audioDelayMs", audioDelayMs)
                put("screenBrightness", screenBrightness)
                put("screenMode", screenMode)
                put("doubleScreenEnabled", doubleScreenEnabled)
                put("tableBroadcastEnabled", tableBroadcastEnabled)
                put("tableBroadcastSeconds", tableBroadcastSeconds)
                put("tableBroadcastText", tableBroadcastText)
                put("orderQueue", writeSongs(orderQueue))
                put("sangHistory", writeSongs(sangHistory))
                put("scoreHistory", JSONArray(scoreHistory.take(100)))
                put("broadcastHistory", JSONArray(broadcastHistory.take(100)))
                put("favoriteIds", JSONArray(favoriteIds.toList()))
                put("hiddenSongIds", JSONArray(hiddenSongIds.toList()))
                put("playlists", JSONArray(playlists))
                put("playlistSongs", JSONObject().apply {
                    playlistSongs.forEach { (name, ids) -> put(name, JSONArray(ids.toList())) }
                })
            }
            root.toString(2)
        }.getOrDefault("{}")

    fun writeSnapshot(snapshot: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(snapshot)
        }
    }

    fun toggleFavorite(song: Song): Boolean = toggleId(favoriteIds, stableId(song))

    fun isFavorite(song: Song): Boolean = stableId(song) in favoriteIds

    fun togglePlaylistSong(playlist: String, song: Song): Boolean = toggleId(songsInPlaylist(playlist), stableId(song))

    fun isInPlaylist(playlist: String, song: Song): Boolean = stableId(song) in songsInPlaylist(playlist)

    fun songsInPlaylist(playlist: String): MutableSet<String> = playlistSongs.getOrPut(playlist) { LinkedHashSet() }

    private fun ensureDefaultPlaylists() {
        listOf("我的收藏", "常唱歌曲").forEach { if (it !in playlists) playlists += it }
        playlists.forEach(::songsInPlaylist)
    }

    private fun toggleId(ids: MutableSet<String>, id: String): Boolean = if (ids.remove(id)) false else ids.add(id)

    companion object {
        @JvmStatic
        fun stableId(song: Song): String = song.id?.takeIf(String::isNotEmpty)
            ?: song.path?.takeIf(String::isNotEmpty)
            ?: "${song.title}|${song.singer}"

        private fun readSongs(array: JSONArray?, out: MutableList<Song>) {
            out.clear()
            if (array == null) return
            repeat(array.length()) { out += Song.fromJson(array.getJSONObject(it)) }
        }

        private fun writeSongs(songs: List<Song>): JSONArray = JSONArray().apply {
            songs.take(200).forEach { put(it.toJson()) }
        }

        private fun JSONArray?.copyStringsTo(out: MutableCollection<String>) {
            if (this == null) return
            repeat(length()) { out += optString(it) }
        }
    }
}
