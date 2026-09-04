package com.local.ktv.player

import android.media.MediaPlayer
import com.local.ktv.Song

object VocalSwitchHelper {
    @JvmStatic
    fun switchVocal(player: MediaPlayer?, song: Song?, original: Boolean): Boolean {
        if (player == null || song == null || song.accomp <= 0) return false
        return runCatching {
            val volumes = channelVolumes(song, original)
            player.setVolume(volumes[0], volumes[1])
            true
        }.getOrDefault(false)
    }

    @JvmStatic
    fun channelVolumes(song: Song?, original: Boolean): FloatArray = when (song?.accomp) {
        1 -> if (original) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f)
        2 -> if (original) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        else -> floatArrayOf(1f, 1f)
    }
}
