package com.local.ktv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class KtvStateDatabase(context: Context) :
    SQLiteOpenHelper(context, "ktv_state.db", null, VERSION) {

    data class RestoredState(val currentSong: Song?, val playbackState: String)

    data class DownloadSnapshot(
        val song: Song,
        val status: String,
        val progress: Int,
        val localPath: String?,
        val error: String?,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (`key` TEXT PRIMARY KEY, `value` TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE order_media (
                position INTEGER PRIMARY KEY,
                stable_id TEXT NOT NULL UNIQUE,
                song_json TEXT NOT NULL,
                is_current INTEGER NOT NULL DEFAULT 0,
                playback_state TEXT NOT NULL DEFAULT 'queued',
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE sang_history (
                position INTEGER PRIMARY KEY,
                stable_id TEXT NOT NULL,
                song_json TEXT NOT NULL,
                sang_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE song_clicks (
                stable_id TEXT PRIMARY KEY,
                title TEXT,
                singer TEXT,
                click_count INTEGER NOT NULL DEFAULT 0,
                click_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE download_logs (
                stable_id TEXT PRIMARY KEY,
                song_json TEXT NOT NULL,
                status TEXT NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                local_path TEXT,
                error TEXT,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun restoreOrSeed(queue: MutableList<Song>, history: MutableList<Song>): RestoredState {
        val db = writableDatabase
        if (!isInitialized(db)) {
            db.transaction {
                syncQueueInternal(this, queue, history, null, "idle")
                insertWithOnConflict(
                    "meta",
                    null,
                    ContentValues().apply {
                        put("key", "initialized")
                        put("value", "1")
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            return RestoredState(null, "idle")
        }

        queue.clear()
        history.clear()
        var current: Song? = null
        var playbackState = "idle"
        db.query(
            "order_media",
            arrayOf("song_json", "is_current", "playback_state"),
            null,
            null,
            null,
            null,
            "position ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val song = Song.fromJson(org.json.JSONObject(cursor.getString(0)))
                queue += song
                if (cursor.getInt(1) == 1) {
                    current = song
                    playbackState = cursor.getString(2)
                }
            }
        }
        db.query(
            "sang_history",
            arrayOf("song_json"),
            null,
            null,
            null,
            null,
            "position ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                history += Song.fromJson(org.json.JSONObject(cursor.getString(0)))
            }
        }
        return RestoredState(current, playbackState)
    }

    @Synchronized
    fun syncQueue(queue: List<Song>, history: List<Song>, current: Song?, playbackState: String) {
        writableDatabase.transaction {
            syncQueueInternal(this, queue, history, current, playbackState)
        }
    }

    @Synchronized
    fun markSongClicked(song: Song) {
        val db = writableDatabase
        val id = KtvStore.stableId(song)
        db.execSQL(
            """INSERT OR REPLACE INTO song_clicks(stable_id,title,singer,click_count,click_at)
               VALUES(?,?,?,COALESCE((SELECT click_count FROM song_clicks WHERE stable_id=?),0)+1,?)""".trimIndent(),
            arrayOf(id, song.title, song.singer, id, System.currentTimeMillis()),
        )
    }

    @Synchronized
    fun updateDownload(
        song: Song,
        status: String,
        progress: Int,
        localPath: String? = null,
        error: String? = null,
    ) {
        writableDatabase.insertWithOnConflict(
            "download_logs",
            null,
            ContentValues().apply {
                put("stable_id", KtvStore.stableId(song))
                put("song_json", song.toJson().toString())
                put("status", status)
                put("progress", progress.coerceIn(0, 100))
                put("local_path", localPath)
                put("error", error)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun restoreDownloads(): List<DownloadSnapshot> = buildList {
        readableDatabase.query(
            "download_logs",
            arrayOf("song_json", "status", "progress", "local_path", "error"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    DownloadSnapshot(
                        Song.fromJson(org.json.JSONObject(cursor.getString(0))),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getString(3),
                        cursor.getString(4),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun removeDownload(song: Song) {
        writableDatabase.delete("download_logs", "stable_id=?", arrayOf(KtvStore.stableId(song)))
    }

    @Synchronized
    fun clearDownloads() {
        writableDatabase.delete("download_logs", null, null)
    }

    private fun syncQueueInternal(
        db: SQLiteDatabase,
        queue: List<Song>,
        history: List<Song>,
        current: Song?,
        playbackState: String,
    ) {
        val now = System.currentTimeMillis()
        val currentId = current?.let(KtvStore::stableId)
        db.delete("order_media", null, null)
        queue.take(200).forEachIndexed { position, song ->
            val stableId = KtvStore.stableId(song)
            db.insertOrThrow(
                "order_media",
                null,
                ContentValues().apply {
                    put("position", position)
                    put("stable_id", stableId)
                    put("song_json", song.toJson().toString())
                    put("is_current", if (stableId == currentId) 1 else 0)
                    put("playback_state", if (stableId == currentId) playbackState else "queued")
                    put("updated_at", now)
                },
            )
        }
        db.delete("sang_history", null, null)
        history.take(200).forEachIndexed { position, song ->
            db.insertOrThrow(
                "sang_history",
                null,
                ContentValues().apply {
                    put("position", position)
                    put("stable_id", KtvStore.stableId(song))
                    put("song_json", song.toJson().toString())
                    put("sang_at", now)
                },
            )
        }
    }

    private fun isInitialized(db: SQLiteDatabase): Boolean = db.query(
        "meta",
        arrayOf("value"),
        "`key`=?",
        arrayOf("initialized"),
        null,
        null,
        null,
        "1",
    ).use { it.moveToFirst() }

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val VERSION = 1
    }
}
