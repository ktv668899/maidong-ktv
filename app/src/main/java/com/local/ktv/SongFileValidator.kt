package com.local.ktv

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Rejects complete-looking CDN previews that pass the old size-only check. */
object SongFileValidator {
    const val MIN_VALID_FILE_SIZE = 6L * 1024L * 1024L
    const val MIN_SONG_DURATION_MS = 60_000L
    private const val TS_PACKET_SIZE = 188
    private const val PROBE_PACKET_COUNT = 24_000 // about 4.3 MiB at each end
    private const val PTS_WRAP = 1L shl 33

    data class Result(
        val valid: Boolean,
        val durationMs: Long? = null,
        val reason: String = "",
    )

    private data class Cached(val length: Long, val modified: Long, val result: Result)
    private val cache = ConcurrentHashMap<String, Cached>()

    fun inspect(file: File, requireTransportStream: Boolean = true): Result {
        if (!file.isFile) return Result(false, reason = "file missing")
        val key = file.absolutePath
        cache[key]?.takeIf { it.length == file.length() && it.modified == file.lastModified() }?.let {
            return it.result
        }
        val result = inspectUncached(file, requireTransportStream)
        cache[key] = Cached(file.length(), file.lastModified(), result)
        return result
    }

    fun forget(file: File) {
        cache.remove(file.absolutePath)
    }

    fun requiresTransportStream(file: File): Boolean =
        file.extension.equals("ts", true) || file.extension.equals("ls", true)

    private fun inspectUncached(file: File, requireTransportStream: Boolean): Result {
        if (file.length() < MIN_VALID_FILE_SIZE) {
            return Result(false, reason = "file too small: ${file.length()}")
        }
        RandomAccessFile(file, "r").use { input ->
            val syncOffset = findSyncOffset(input)
            if (syncOffset < 0) {
                return if (requireTransportStream) Result(false, reason = "not an MPEG-TS file") else Result(true)
            }
            val packetCount = ((input.length() - syncOffset) / TS_PACKET_SIZE).coerceAtLeast(0)
            if (packetCount < 10) return Result(false, reason = "incomplete MPEG-TS file")
            val first = readPts(input, syncOffset, minOf(packetCount, PROBE_PACKET_COUNT.toLong()).toInt())
            val tailPackets = minOf(packetCount, PROBE_PACKET_COUNT.toLong()).toInt()
            val tailOffset = syncOffset + (packetCount - tailPackets) * TS_PACKET_SIZE
            val last = readPts(input, tailOffset, tailPackets)
            if (first.isEmpty() || last.isEmpty()) {
                // Some valid transport streams omit PTS in the sampled range; keep the old size check in that case.
                return Result(true, reason = "duration unavailable")
            }
            val startPts = first.minOrNull() ?: return Result(true, reason = "duration unavailable")
            val endPts = last.maxOrNull() ?: return Result(true, reason = "duration unavailable")
            val ticks = if (endPts >= startPts) endPts - startPts else endPts + PTS_WRAP - startPts
            val durationMs = ticks * 1_000L / 90_000L
            return if (durationMs < MIN_SONG_DURATION_MS) {
                Result(false, durationMs, "preview too short: ${durationMs}ms")
            } else {
                Result(true, durationMs)
            }
        }
    }

    private fun findSyncOffset(input: RandomAccessFile): Long {
        val probeSize = minOf(input.length(), TS_PACKET_SIZE.toLong() * 8).toInt()
        if (probeSize < TS_PACKET_SIZE * 3) return -1
        val probe = ByteArray(probeSize)
        input.seek(0)
        input.readFully(probe)
        for (offset in 0 until minOf(TS_PACKET_SIZE, probe.size)) {
            var matches = 0
            var index = offset
            while (index < probe.size && matches < 5) {
                if (probe[index].toInt() and 0xff != 0x47) break
                matches++
                index += TS_PACKET_SIZE
            }
            if (matches >= 3) return offset.toLong()
        }
        return -1
    }

    private fun readPts(input: RandomAccessFile, offset: Long, count: Int): List<Long> {
        val values = ArrayList<Long>()
        val packet = ByteArray(TS_PACKET_SIZE)
        input.seek(offset)
        repeat(count) {
            if (input.read(packet) != TS_PACKET_SIZE) return@repeat
            if ((packet[0].toInt() and 0xff) != 0x47 || (packet[1].toInt() and 0x40) == 0) return@repeat
            val adaptationControl = packet[3].toInt().ushr(4) and 0x03
            if (adaptationControl != 1 && adaptationControl != 3) return@repeat
            var payload = 4
            if (adaptationControl == 3) payload += 1 + (packet[4].toInt() and 0xff)
            if (payload + 14 > TS_PACKET_SIZE) return@repeat
            if (packet[payload].toInt() != 0 || packet[payload + 1].toInt() != 0 ||
                packet[payload + 2].toInt() and 0xff != 1
            ) return@repeat
            if ((packet[payload + 7].toInt() and 0x80) == 0) return@repeat
            val p = payload + 9
            val pts = (((packet[p].toLong() ushr 1) and 0x07) shl 30) or
                ((packet[p + 1].toLong() and 0xff) shl 22) or
                (((packet[p + 2].toLong() ushr 1) and 0x7f) shl 15) or
                ((packet[p + 3].toLong() and 0xff) shl 7) or
                ((packet[p + 4].toLong() ushr 1) and 0x7f)
            values += pts
        }
        return values
    }
}
