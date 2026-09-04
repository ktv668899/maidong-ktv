package com.local.ktv

import org.json.JSONObject
import java.io.File

class Song {
    @JvmField var id: String? = null
    @JvmField var title: String? = null
    @JvmField var singer: String? = null
    @JvmField var category: String? = null
    @JvmField var language: String? = null
    @JvmField var pinyin: String? = null
    @JvmField var album: String? = null
    @JvmField var path: String? = null
    @JvmField var downloadUrl: String? = null
    @JvmField var videoUrl: String? = null
    @JvmField var originalUrl: String? = null
    @JvmField var accompanyUrl: String? = null
    @JvmField var lyricUrl: String? = null
    @JvmField var originalPath: String? = null
    @JvmField var accompanyPath: String? = null
    @JvmField var lyricPath: String? = null
    @JvmField var remote = false
    @JvmField var playCount = 0

    @JvmField var dbId: String? = null
    @JvmField var pinyinFull: String? = null
    @JvmField var filename: String? = null
    @JvmField var dbPath: String? = null
    @JvmField var accomp = 0
    @JvmField var hotScore = 0
    @JvmField var localHotScore = 0
    @JvmField var singerNames: String? = null
    @JvmField var pOrigin: String? = null
    @JvmField var pAccomp: String? = null
    @JvmField var oModeChannels: String? = null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("singer", singer)
        put("category", category)
        put("language", language)
        put("pinyin", pinyin)
        put("album", album)
        put("path", path)
        put("downloadUrl", downloadUrl)
        put("videoUrl", videoUrl)
        put("originalUrl", originalUrl)
        put("accompanyUrl", accompanyUrl)
        put("lyricUrl", lyricUrl)
        put("originalPath", originalPath)
        put("accompanyPath", accompanyPath)
        put("lyricPath", lyricPath)
        put("remote", remote)
        put("playCount", playCount)
        dbId?.let { put("dbId", it) }
        pinyinFull?.let { put("pinyinFull", it) }
        filename?.let { put("filename", it) }
        dbPath?.let { put("dbPath", it) }
        if (accomp != 0) put("accomp", accomp)
        if (hotScore != 0) put("hotScore", hotScore)
        if (localHotScore != 0) put("localHotScore", localHotScore)
        singerNames?.let { put("singerNames", it) }
        pOrigin?.let { put("pOrigin", it) }
        pAccomp?.let { put("pAccomp", it) }
        oModeChannels?.let { put("oModeChannels", it) }
    }

    fun displayTitle(): String = "${title.orEmpty()}  -  ${singer.orEmpty()}"

    fun hasLocalFile(): Boolean = path?.takeIf(String::isNotEmpty)?.let(::File)?.let { file ->
        SongFileValidator.inspect(file, SongFileValidator.requiresTransportStream(file)).valid
    } == true

    fun getDownloadUrl(): String {
        downloadUrl?.takeIf(String::isNotEmpty)?.let { return it }
        SongApiClient.getSongDownloadUrl(filename?.substringBeforeLast('.') ?: id)?.takeIf(String::isNotEmpty)?.let {
            downloadUrl = it
            return it
        }
        return filename?.takeIf(String::isNotEmpty)
            ?.let { "https://pub.cdn.cherryonline.cn/video/cloud-song/$it" }
            .orEmpty()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Song) return false
        if (id != null && id == other.id) return true
        if (filename != null && filename == other.filename) return true
        if (path != null && path == other.path) return true
        return title != null && title == other.title && singer != null && singer == other.singer
    }

    override fun hashCode(): Int = id?.hashCode() ?: filename?.hashCode() ?: title?.hashCode() ?: 0

    companion object {
        @JvmStatic
        fun local(path: String, name: String): Song = Song().apply {
            val base = stripExt(name)
            val parsed = parseName(base)
            id = path
            this.path = path
            title = parsed.first
            singer = parsed.second
            val context = "$path $base"
            category = inferCategory(context)
            language = inferLanguage(context)
            pinyin = initials("$title $singer")
            album = ""
            originalPath = existingSidecar(path, ".original.mp3", ".original.aac", ".original.m4a")
            accompanyPath = existingSidecar(path, ".accompany.mp3", ".accompany.aac", ".accompany.m4a")
            lyricPath = existingSidecar(path, ".lrc", ".ass", ".srt")
        }

        @JvmStatic
        fun remote(json: JSONObject): Song = Song().apply {
            id = json.optString("id", json.optString("url"))
            title = json.optString("title", json.optString("name", "未命名歌曲"))
            singer = json.optString("singer", "网络曲库")
            category = json.optString("category", "网络")
            language = json.optString("language", "国语")
            pinyin = json.optString("pinyin", initials(title.orEmpty()))
            album = json.optString("album", "")
            playCount = json.optInt("playCount", 0)
            videoUrl = firstNonEmpty(json.optString("videoUrl"), json.optString("mvUrl"), json.optString("url"), json.optString("downloadUrl"))
            downloadUrl = firstNonEmpty(json.optString("downloadUrl"), videoUrl)
            originalUrl = firstNonEmpty(json.optString("originalUrl"), json.optString("vocalUrl"), json.optString("origUrl"))
            accompanyUrl = firstNonEmpty(json.optString("accompanyUrl"), json.optString("accompanimentUrl"), json.optString("伴奏Url"))
            lyricUrl = firstNonEmpty(json.optString("lyricUrl"), json.optString("lrcUrl"), json.optString("subtitleUrl"))
            remote = true
        }

        @JvmStatic
        fun fromJson(json: JSONObject): Song = Song().apply {
            id = json.optString("id")
            title = json.optString("title", "未命名歌曲")
            singer = json.optString("singer", "未知歌手")
            category = json.optString("category", "其他")
            language = json.optString("language", "国语")
            pinyin = json.optString("pinyin", initials(title.orEmpty()))
            album = json.optString("album", "")
            path = json.optString("path", "")
            downloadUrl = json.optString("downloadUrl", json.optString("url", ""))
            videoUrl = json.optString("videoUrl", downloadUrl.orEmpty())
            originalUrl = json.optString("originalUrl", "")
            accompanyUrl = json.optString("accompanyUrl", "")
            lyricUrl = json.optString("lyricUrl", "")
            originalPath = json.optString("originalPath", "")
            accompanyPath = json.optString("accompanyPath", "")
            lyricPath = json.optString("lyricPath", "")
            remote = json.optBoolean("remote", false)
            playCount = json.optInt("playCount", 0)
            dbId = json.optNullableString("dbId")
            pinyinFull = json.optNullableString("pinyinFull")
            filename = json.optNullableString("filename")
            dbPath = json.optNullableString("dbPath")
            accomp = json.optInt("accomp", 0)
            hotScore = json.optInt("hotScore", 0)
            localHotScore = json.optInt("localHotScore", 0)
            singerNames = json.optNullableString("singerNames")
            pOrigin = json.optNullableString("pOrigin")
            pAccomp = json.optNullableString("pAccomp")
            oModeChannels = json.optNullableString("oModeChannels")
        }

        private fun JSONObject.optNullableString(key: String): String? =
            if (has(key) && !isNull(key)) optString(key) else null

        private fun stripExt(name: String): String = name.lastIndexOf('.').let { if (it > 0) name.substring(0, it) else name }

        private fun existingSidecar(mediaPath: String, vararg suffixes: String): String {
            val base = stripExt(mediaPath)
            return suffixes.asSequence().map { File(base + it) }.firstOrNull(File::exists)?.absolutePath.orEmpty()
        }

        private fun firstNonEmpty(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

        private fun parseName(value: String): Pair<String, String> {
            var clean = value.replace(Regex("\\s+"), " ").trim()
            clean = clean.replace(Regex("^[0-9]+[.、_\\- ]+"), "").trim()
            for (separator in listOf(" - ", "-", "_", "－", "—", "–")) {
                val index = clean.indexOf(separator)
                if (index <= 0 || index >= clean.length - separator.length) continue
                val first = clean.substring(0, index).trim()
                val second = clean.substring(index + separator.length).trim()
                if (first.isEmpty() || second.isEmpty()) continue
                if (looksLikeSinger(first, second)) return second to first
                if (looksLikeSinger(second, first)) return first to second
                return if (first.length <= second.length) first to second else second to first
            }
            return (clean.ifEmpty { "未命名歌曲" }) to "本地歌曲"
        }

        private fun looksLikeSinger(singer: String, title: String): Boolean {
            val value = singer.lowercase()
            if (containsAny(value, "群星", "合唱", "乐队", "组合")) return true
            if (containsAny(value, "mv", "伴奏", "karaoke")) return false
            return singer.length <= 8 && title.length >= 2
        }

        private fun inferLanguage(context: String): String {
            val text = context.lowercase()
            return when {
                containsAny(text, "粤语", "广东", "香港", "cantonese") -> "粤语"
                containsAny(text, "闽南", "台语", "福建", "hokkien") -> "闽南语"
                containsAny(text, "英语", "英文", "欧美", "english", "us", "uk") -> "英语"
                containsAny(text, "日语", "日本", "japanese") -> "日语"
                containsAny(text, "韩语", "韩国", "korean") -> "韩语"
                containsAny(text, "外语", "foreign") -> "外语"
                else -> "国语"
            }
        }

        private fun inferCategory(context: String): String {
            val text = context.lowercase()
            return when {
                containsAny(text, "dj", "舞曲", "disco", "慢摇", "热舞") -> "DJ舞曲"
                containsAny(text, "儿歌", "儿童", "少儿") -> "儿歌"
                containsAny(text, "戏曲", "京剧", "豫剧", "黄梅戏", "越剧") -> "戏曲"
                containsAny(text, "民歌", "民族") -> "民歌"
                containsAny(text, "军歌", "红歌") -> "红歌"
                containsAny(text, "经典", "老歌", "怀旧") -> "经典老歌"
                containsAny(text, "新歌", "流行", "pop") -> "流行"
                containsAny(text, "粤语", "英语", "日语", "韩语", "闽南", "外语", "欧美", "日韩") -> "语种歌曲"
                else -> "本地"
            }
        }

        private fun containsAny(text: String, vararg keys: String): Boolean = keys.any { text.contains(it.lowercase()) }

        private fun initials(value: String): String = buildString {
            value.forEach { ch ->
                when (ch) {
                    in 'A'..'Z', in '0'..'9' -> append(ch)
                    in 'a'..'z' -> append(ch.uppercaseChar())
                }
            }
        }.ifEmpty { value }
    }
}
