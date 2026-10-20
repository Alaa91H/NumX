package com.numx.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Stats(
    val totalGenerated: Long = 0,
    val totalInserted: Long = 0,
    val totalDeleted: Long = 0,
    val totalSessions: Long = 0,
    val addedCount: Long = 0,
    val lastRatePerSecond: Double = 0.0,
)

data class RestoreResult(
    val found: Long,
    val lastNumber: String?,
)

class NumXDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        // WAL + NORMAL is the standard high-throughput pairing: durable against
        // app crashes, while avoiding a full fsync for every batch.
        db.execSQL("PRAGMA synchronous=NORMAL")
        db.execSQL("PRAGMA temp_store=MEMORY")
        db.execSQL("PRAGMA cache_size=-20000")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                start_number TEXT NOT NULL,
                requested_count INTEGER NOT NULL,
                generated_count INTEGER NOT NULL DEFAULT 0,
                inserted_count INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                duration_ms INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE added_numbers (
                raw_contact_id INTEGER PRIMARY KEY,
                number TEXT NOT NULL,
                session_id INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // No secondary indexes: every query uses the raw_contact_id primary key
        // (rowid), counts rows, or scans in PK order. Avoiding the extra B-trees
        // makes bulk inserts faster and saves substantial storage at 1M+ rows.
        db.execSQL("CREATE TABLE counters (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL(
            """
            INSERT INTO counters(key, value) VALUES
                ('total_generated', 0),
                ('total_inserted', 0),
                ('total_deleted', 0),
                ('total_sessions', 0),
                ('last_rate_milli', 0)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 2 removes unused secondary indexes from added_numbers.
            db.execSQL("DROP INDEX IF EXISTS idx_added_numbers_session")
            db.execSQL("DROP INDEX IF EXISTS idx_added_numbers_number")
        }
    }

    fun createSession(startNumber: String, requestedCount: Int): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("started_at", System.currentTimeMillis())
                put("start_number", startNumber)
                put("requested_count", requestedCount)
                put("status", "running")
            }
            val id = db.insertOrThrow("sessions", null, values)
            incrementCounter(db, "total_sessions", 1)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun finishSession(
        sessionId: Long,
        status: String,
        generated: Long,
        inserted: Long,
        durationMs: Long,
        ratePerSecond: Double,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("finished_at", System.currentTimeMillis())
                put("generated_count", generated)
                put("inserted_count", inserted)
                put("status", status)
                put("duration_ms", durationMs)
            }
            db.update("sessions", values, "id = ?", arrayOf(sessionId.toString()))
            if (ratePerSecond > 0) {
                setCounter(db, "last_rate_milli", (ratePerSecond * 1000).toLong())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Stores inserted contact IDs and updates counters in a single transaction.
     * numbers and rawContactIds must have the same size and ordering.
     */
    fun recordInsertedBatch(
        sessionId: Long,
        numbers: List<String>,
        rawContactIds: List<Long>,
        generatedCount: Long = numbers.size.toLong(),
    ) {
        if (numbers.isEmpty() || numbers.size != rawContactIds.size) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO added_numbers(raw_contact_id, number, session_id, created_at) VALUES(?,?,?,?)"
            )
            try {
                for (i in numbers.indices) {
                    stmt.bindLong(1, rawContactIds[i])
                    stmt.bindString(2, numbers[i])
                    stmt.bindLong(3, sessionId)
                    stmt.bindLong(4, now)
                    stmt.execute()
                }
            } finally {
                stmt.close()
            }
            val insertedCount = numbers.size.toLong()
            db.execSQL(
                "UPDATE sessions SET generated_count = generated_count + ?, inserted_count = inserted_count + ? WHERE id = ?",
                arrayOf(generatedCount, insertedCount, sessionId),
            )
            incrementCounter(db, "total_generated", generatedCount)
            incrementCounter(db, "total_inserted", insertedCount)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun countAdded(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM added_numbers", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }

    fun loadAddedIdBatch(limit: Int): List<Long> {
        val ids = ArrayList<Long>(limit)
        readableDatabase.rawQuery(
            "SELECT raw_contact_id FROM added_numbers ORDER BY raw_contact_id LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    fun deleteAddedRecords(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val placeholders = ids.joinToString(",") { "?" }
            val args = ids.map { it.toString() }.toTypedArray()
            db.delete("added_numbers", "raw_contact_id IN ($placeholders)", args)
            incrementCounter(db, "total_deleted", ids.size.toLong())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun stats(): Stats {
        val values = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT key, value FROM counters", null).use { c ->
            while (c.moveToNext()) values[c.getString(0)] = c.getLong(1)
        }
        return Stats(
            totalGenerated = values["total_generated"] ?: 0,
            totalInserted = values["total_inserted"] ?: 0,
            totalDeleted = values["total_deleted"] ?: 0,
            totalSessions = values["total_sessions"] ?: 0,
            addedCount = countAdded(),
            lastRatePerSecond = (values["last_rate_milli"] ?: 0L) / 1000.0,
        )
    }

    fun resetStats() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sessions", null, null)
            db.execSQL(
                """
                UPDATE counters SET value = 0
                WHERE key IN ('total_generated','total_inserted','total_deleted','total_sessions','last_rate_milli')
                """.trimIndent()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Builds a disk-backed lookup of every phone number currently saved on the
     * device. A regular SQLite table is used (not a TEMP table or an in-memory
     * HashSet): Android's SQLiteDatabase pools connections, and TEMP tables are
     * scoped to one connection. A regular table is visible from every pooled
     * connection and keeps memory flat even with millions of saved contacts.
     */
    fun prepareExistingNumberLookup() {
        val db = writableDatabase
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS existing_numbers(number TEXT PRIMARY KEY)"
        )
        db.delete("existing_numbers", null, null)
    }

    fun populateExistingNumberLookup(
        rows: Sequence<String>,
        onProgress: (scanned: Long) -> Unit = {},
    ): Long {
        val db = writableDatabase
        var scanned = 0L
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR IGNORE INTO existing_numbers(number) VALUES(?)"
            )
            try {
                rows.forEach { number ->
                    if (number.isBlank()) return@forEach
                    stmt.bindString(1, number)
                    stmt.executeInsert()
                    scanned++
                    if (scanned % 5_000 == 0L) onProgress(scanned)
                }
            } finally {
                stmt.close()
            }
            val found = db.rawQuery("SELECT COUNT(*) FROM existing_numbers", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            onProgress(scanned)
            db.setTransactionSuccessful()
            return found
        } finally {
            db.endTransaction()
        }
    }

    /** Returns the subset of [candidates] that is already saved on the device. */
    fun existingNumbers(candidates: List<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val db = readableDatabase
        val found = HashSet<String>()
        for (chunk in candidates.chunked(400)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT number FROM existing_numbers WHERE number IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) found.add(cursor.getString(0))
            }
        }
        return found
    }

    fun clearExistingNumberLookup() {
        runCatching { writableDatabase.delete("existing_numbers", null, null) }
    }

    /**
     * Rebuilds the ownership table from contacts that carry the neutral note
     * marker. Existing rows are replaced atomically so the operation can be
     * repeated safely. Counters are only raised, never reduced, to preserve
     * historical totals when a previously deleted contact is no longer found.
     */
    fun restoreAddedContacts(
        rows: Sequence<OwnedContact>,
        onProgress: (scanned: Long) -> Unit = {},
    ): RestoreResult {
        val db = writableDatabase
        var scanned = 0L
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete("added_numbers", null, null)
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO added_numbers(raw_contact_id, number, session_id, created_at) VALUES(?,?,?,?)"
            )
            try {
                rows.forEach { row ->
                    if (!SyrianNumbers.isValid(row.number)) return@forEach
                    stmt.bindLong(1, row.rawContactId)
                    stmt.bindString(2, row.number)
                    stmt.bindNull(3)
                    stmt.bindLong(4, now)
                    stmt.executeInsert()
                    scanned++
                    if (scanned % 1_000 == 0L) onProgress(scanned)
                }
            } finally {
                stmt.close()
            }
            val found = db.rawQuery("SELECT COUNT(*) FROM added_numbers", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            val last = db.rawQuery("SELECT MAX(number) FROM added_numbers", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
            if (found > getCounter(db, "total_generated")) {
                setCounter(db, "total_generated", found)
            }
            if (found > getCounter(db, "total_inserted")) {
                setCounter(db, "total_inserted", found)
            }
            db.setTransactionSuccessful()
            onProgress(scanned)
            return RestoreResult(found, last)
        } finally {
            db.endTransaction()
        }
    }

    fun exportCursor(): Cursor =
        readableDatabase.rawQuery("SELECT number FROM added_numbers ORDER BY raw_contact_id", null)

    private fun getCounter(db: SQLiteDatabase, key: String): Long =
        db.rawQuery("SELECT value FROM counters WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun incrementCounter(db: SQLiteDatabase, key: String, delta: Long) {
        db.execSQL("UPDATE counters SET value = value + ? WHERE key = ?", arrayOf<Any>(delta, key))
    }

    private fun setCounter(db: SQLiteDatabase, key: String, value: Long) {
        db.execSQL("UPDATE counters SET value = ? WHERE key = ?", arrayOf<Any>(value, key))
    }

    companion object {
        private const val DB_NAME = "data.db"
        private const val DB_VERSION = 2
    }
}