package tv.ezano.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class EventWindow(val label: String) { NOW("On now"), TODAY("Today"), WEEK("Next 7 days") }
data class EventResults(val programs: List<Program> = emptyList(), val total: Int = 0)

/** User input becomes literal prefix terms, never FTS operators or SQL. */
object SearchTerms {
    private val marks = Regex("\\p{M}+")
    private val words = Regex("[\\p{L}\\p{N}]+")
    fun tokens(query: String): List<String> = words.findAll(marks.replace(
        Normalizer.normalize(query.take(256), Normalizer.Form.NFD), "")
        .lowercase(Locale.ROOT)).map { it.value }.distinct().take(12).toList()
    // Implicit AND works on both standard and enhanced Android FTS4 builds.
    // FTS4 prefix markers belong inside a quoted phrase (FTS5 differs).
    fun fts(query: String): String = tokens(query).joinToString(" ") { "\"$it*\"" }
    fun ceiling(window: EventWindow, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when(window) {
        EventWindow.NOW -> now
        EventWindow.TODAY -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        EventWindow.WEEK -> now + 7 * 86_400_000L
    }
}

/** Rebuildable, app-private metadata cache. Credentials and stream URLs never enter this DB.
 * A transaction replaces one immutable guide snapshot; a canceled rebuild rolls back.
 * Searching hydrates only the requested page and never inflates compressed guide pages.
 */
class EventSearchIndex(context: Context, name: String = "event-search-v1.db") {
    private val path = java.io.File(context.cacheDir, name)
    private val mutex = Mutex()
    private var indexed: List<Program>? = null
    private val db by lazy {
        SQLiteDatabase.openOrCreateDatabase(path, null).apply {
            enableWriteAheadLogging()
            execSQL("CREATE TABLE IF NOT EXISTS events (rowid INTEGER PRIMARY KEY, id TEXT NOT NULL, channel TEXT NOT NULL, starts INTEGER NOT NULL, ends INTEGER NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL)")
            execSQL("CREATE INDEX IF NOT EXISTS event_times ON events(ends,starts)")
            execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS event_text USING fts4(title,description,tokenize=unicode61,prefix='2,3,4')")
            execSQL("CREATE TABLE IF NOT EXISTS event_meta (id INTEGER PRIMARY KEY CHECK (id=1), signature TEXT NOT NULL)")
        }
    }

    suspend fun prepare(programs: List<Program>) = withContext(Dispatchers.IO) {
        mutex.withLock { prepareLocked(programs) }
    }

    /** Identifies a guide snapshot without decoding it; reopening an unchanged guide reuses the index. */
    private fun signature(programs: List<Program>): String =
        if(programs.isEmpty()) "empty" else "v1:${programs.size}:${programs.first().id}:${programs[programs.size/2].id}:${programs.last().id}"

    private suspend fun prepareLocked(programs: List<Program>) {
        if(indexed === programs) return
        val signature = signature(programs)
        val stored = db.rawQuery("SELECT signature FROM event_meta WHERE id=1", null).use { if(it.moveToFirst()) it.getString(0) else null }
        if(stored == signature) { indexed = programs; return }
        val coroutine = currentCoroutineContext()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events")
            db.execSQL("DELETE FROM event_text")
            db.compileStatement("INSERT INTO events VALUES (?,?,?,?,?,?,?)").use { row ->
                db.compileStatement("INSERT INTO event_text(docid,title,description) VALUES (?,?,?)").use { text ->
                    programs.forEachIndexed { index, p ->
                        if(index % 128 == 0) coroutine.ensureActive()
                        val key = index.toLong() + 1
                        row.clearBindings(); row.bindLong(1,key); row.bindString(2,p.id)
                        row.bindString(3,p.channelId); row.bindLong(4,p.start); row.bindLong(5,p.end)
                        row.bindString(6,p.title); row.bindString(7,p.description); row.executeInsert()
                        text.clearBindings(); text.bindLong(1,key); text.bindString(2,p.title)
                        text.bindString(3,p.description); text.executeInsert()
                    }
                }
            }
            coroutine.ensureActive()
            db.execSQL("INSERT OR REPLACE INTO event_meta(id,signature) VALUES (1,?)", arrayOf(signature))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        indexed = programs
    }

    suspend fun search(programs: List<Program>, query: String, window: EventWindow, now: Long,
        allowedChannels: Set<String>, limit: Int = 60): EventResults = withContext(Dispatchers.IO) {
        val match = SearchTerms.fts(query)
        if(match.isEmpty() || allowedChannels.isEmpty()) return@withContext EventResults()
        mutex.withLock {
            prepareLocked(programs)
            val hits = ArrayList<Program>(minOf(limit,60))
            var count = 0
            val ceiling = SearchTerms.ceiling(window,now)
            val time = if(window == EventWindow.NOW) "e.starts<=? AND e.ends>?" else "e.starts<? AND e.ends>?"
            db.rawQuery("SELECT e.id,e.channel,e.title,e.starts,e.ends,e.description FROM event_text JOIN events e ON e.rowid=event_text.docid WHERE event_text MATCH ? AND $time ORDER BY e.starts,e.channel,e.rowid",
                arrayOf(match,ceiling.toString(),now.toString())).use { cursor ->
                val coroutine = currentCoroutineContext()
                while(cursor.moveToNext()) {
                    if(cursor.position % 128 == 0) coroutine.ensureActive()
                    if(cursor.getString(1) !in allowedChannels) continue
                    count++
                    if(hits.size < limit.coerceIn(1,1000)) hits += Program(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getLong(3),cursor.getLong(4),cursor.getString(5))
                }
            }
            EventResults(hits,count)
        }
    }
}
