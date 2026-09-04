package com.local.ktv

class DownloadTask(@JvmField val song: Song) {
    @JvmField var state: String = "等待"
    @JvmField var progress: Int = 0
    @JvmField var cancelled: Boolean = false

    fun display(): String = buildString {
        append(song.title).append(" - ").append(song.singer).append("    ").append(state)
        if (progress > 0) append(' ').append(progress).append('%')
    }
}
