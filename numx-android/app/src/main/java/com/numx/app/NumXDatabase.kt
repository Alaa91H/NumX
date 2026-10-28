package com.numx.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

data class Stats(
    val totalGenerated: Long = 0,
    val totalInserted: Long = 0,
    val totalDeleted: Long = 0,
    val totalSessions: Long = 0,
    val addedCount: Long = 0,
    val lastRatePerSecond: Double = 0.0,
    val mobileGenerated: Long = 0,
    val fixedGenerated: Long = 0,
    val emergencyAdded: Long = 0,
)

data class RestoreResult(
    val found: Long,
    val lastNumber: String?,
    val emergencyFound: Long = 0,
    val lastE164: String? = null,
    val lastIso2: String? = null,
    val lastLineType: String? = null,
)

data class InsertedNumber(
    val number: String,
    val e164: String?,
    val iso2: String,
    val lineType: String,
    val kind: String = NumXDatabase.KIND_GENERATED,
)

data class AddedRecord(
    val number: String,
    val e164: String?,
    val iso2: String,
    val lineType: String,
    val kind: String,
)

data class EmergencyRecord(
    val number: String,
    val iso2: String,
    val category: String,
    val label: String,
)

data class OwnedContact(
    val rawContactId: Long,
    val tag: OwnershipTag,
)

class NumXDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
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
                duration_ms INTEGER,
                country_iso TEXT NOT NULL DEFAULT 'SY',
                line_type TEXT NOT NULL DEFAULT 'MOBILE'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE added_numbers (
                raw_contact_id INTEGER PRIMARY KEY,
                number TEXT NOT NULL,
                session_id INTEGER,
                created_at INTEGER NOT NULL,
                e164 TEXT,
                iso2 TEXT NOT NULL DEFAULT 'SY',
                line_type TEXT NOT NULL DEFAULT 'MOBILE',
                kind TEXT NOT NULL DEFAULT 'GENERATED'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE emergency_added (
                raw_contact_id INTEGER PRIMARY KEY,
                number TEXT NOT NULL,
                iso2 TEXT NOT NULL,
                category TEXT NOT NULL,
                label TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE country_cursors (
                iso2 TEXT NOT NULL,
                line_type TEXT NOT NULL,
                last_nsn TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(iso2, line_type)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE counters (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL(
            """
            INSERT INTO counters(key, value) VALUES
                ('total_generated', 0),
                ('total_inserted', 0),
                ('total_deleted', 0),
                ('total_sessions', 0),
                ('last_rate_milli', 0),
                ('mobile_generated', 0),
                ('fixed_generated', 0),
                ('emergency_added', 0)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP INDEX IF EXISTS idx_added_numbers_session")
            db.execSQL("DROP INDEX IF EXISTS idx_added_numbers_number")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN country_iso TEXT NOT NULL DEFAULT 'SY'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN line_type TEXT NOT NULL DEFAULT 'MOBILE'")
            db.execSQL("ALTER TABLE added_numbers ADD COLUMN e164 TEXT")
            db.execSQL("ALTER TABLE added_numbers ADD COLUMN iso2 TEXT NOT NULL DEFAULT 'SY'")
            db.execSQL("ALTER TABLE added_numbers ADD COLUMN line_type TEXT NOT NULL DEFAULT 'MOBILE'")
            db.execSQL("ALTER TABLE added_numbers ADD COLUMN kind TEXT NOT NULL DEFAULT 'GENERATED'")
            db.execSQL(
                """
                UPDATE added_numbers
                SET e164 = '+963' || substr(number, 2)
                WHERE number LIKE '09%' AND length(number) = 10
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS emergency_added (
                    raw_contact_id INTEGER PRIMARY KEY,
                    number TEXT NOT NULL,
                    iso2 TEXT NOT NULL,
                    category TEXT NOT NULL,
                    label TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS country_cursors (
                    iso2 TEXT NOT NULL,
                    line_type TEXT NOT NULL,
                    last_nsn TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(iso2, line_type)
                )
                """.trimIndent()
            )
            for (key in listOf("mobile_generated", "fixed_generated", "emergency_added")) {
                db.execSQL("INSERT OR IGNORE INTO counters(key, value) VALUES(?, 0)", arrayOf(key))
            }
        }
    }

    fun createSession(
        startNumber: String,
        requestedCount: Long,
        countryIso: String = "SY",
        lineType: LineType = LineType.MOBILE,
    ): Long {
        val values = ContentValues().apply {
            put("started_at", System.currentTimeMillis())
            put("start_number", startNumber)
            put("requested_count", requestedCount)
            put("status", "running")
            put("country_iso", countryIso)
            put("line_type", lineType.code)
        }
        return writableDatabase.transaction {
            val id = insertOrThrow("sessions", null, values)
            incrementCounter(this, "total_sessions", 1)
            id
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
        writableDatabase.transaction {
            val values = ContentValues().apply {
                put("finished_at", System.currentTimeMillis())
                put("generated_count", generated)
                put("inserted_count", inserted)
                put("status", status)
                put("duration_ms", durationMs)
            }
            update("sessions", values, "id = ?", arrayOf(sessionId.toString()))
            if (ratePerSecond > 0) {
                setCounter(this, "last_rate_milli", (ratePerSecond * 1000).toLong())
            }
        }
    }

    fun recordInsertedBatch(
        sessionId: Long,
        numbers: List<InsertedNumber>,
        rawContactIds: List<Long>,
        generatedCount: Long = numbers.size.toLong(),
    ) {
        if (numbers.isEmpty() || numbers.size != rawContactIds.size) return
        val now = System.currentTimeMillis()
        writableDatabase.transaction {
            val stmt = compileStatement(
                """
                INSERT OR REPLACE INTO added_numbers(
                    raw_contact_id, number, session_id, created_at, e164, iso2, line_type, kind
                ) VALUES(?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            try {
                var mobile = 0L
                var fixed = 0L
                for (i in numbers.indices) {
                    val item = numbers[i]
                    stmt.bindLong(1, rawContactIds[i])
                    stmt.bindString(2, item.number)
                    stmt.bindLong(3, sessionId)
                    stmt.bindLong(4, now)
                    if (item.e164 == null) stmt.bindNull(5) else stmt.bindString(5, item.e164)
                    stmt.bindString(6, item.iso2)
                    stmt.bindString(7, item.lineType)
                    stmt.bindString(8, item.kind)
                    stmt.execute()
                    when (item.lineType) {
                        ActualLineType.MOBILE.code -> mobile++
                        ActualLineType.FIXED.code -> fixed++
                    }
                }
                if (mobile > 0) incrementCounter(this, "mobile_generated", mobile)
                if (fixed > 0) incrementCounter(this, "fixed_generated", fixed)
            } finally {
                stmt.close()
            }
            val insertedCount = numbers.size.toLong()
            execSQL(
                "UPDATE sessions SET generated_count = generated_count + ?, inserted_count = inserted_count + ? WHERE id = ?",
                arrayOf(generatedCount, insertedCount, sessionId),
            )
            incrementCounter(this, "total_generated", generatedCount)
            incrementCounter(this, "total_inserted", insertedCount)
        }
    }

    /**
     * Records numbers written to a file. These rows are not contacts, so they
     * never enter added_numbers and can never be deleted from the contacts app
     * by NumX.
     */
    fun recordFileGenerated(
        sessionId: Long,
        count: Long,
        mobileCount: Long = 0,
        fixedCount: Long = 0,
    ) {
        if (count <= 0) return
        writableDatabase.transaction {
            execSQL(
                "UPDATE sessions SET generated_count = generated_count + ? WHERE id = ?",
                arrayOf(count, sessionId),
            )
            incrementCounter(this, "total_generated", count)
            if (mobileCount > 0) incrementCounter(this, "mobile_generated", mobileCount)
            if (fixedCount > 0) incrementCounter(this, "fixed_generated", fixedCount)
        }
    }

    /** Records explicitly added emergency contacts, separately from generated numbers. */
    fun recordEmergencyBatch(
        entries: List<EmergencyRecord>,
        rawContactIds: List<Long>,
    ) {
        if (entries.isEmpty() || entries.size != rawContactIds.size) return
        val now = System.currentTimeMillis()
        writableDatabase.transaction {
            val stmt = compileStatement(
                """
                INSERT OR REPLACE INTO emergency_added(
                    raw_contact_id, number, iso2, category, label, created_at
                ) VALUES(?,?,?,?,?,?)
                """.trimIndent()
            )
            try {
                for (i in entries.indices) {
                    val item = entries[i]
                    stmt.bindLong(1, rawContactIds[i])
                    stmt.bindString(2, item.number)
                    stmt.bindString(3, item.iso2)
                    stmt.bindString(4, item.category)
                    stmt.bindString(5, item.label)
                    stmt.bindLong(6, now)
                    stmt.execute()
                }
            } finally {
                stmt.close()
            }
            incrementCounter(this, "emergency_added", entries.size.toLong())
        }
    }

    fun countAdded(): Long = countTable("added_numbers")

    fun countEmergencyAdded(): Long = countTable("emergency_added")

    private fun countTable(table: String): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
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

    fun loadEmergencyIdBatch(limit: Int): List<Long> {
        val ids = ArrayList<Long>(limit)
        readableDatabase.rawQuery(
            "SELECT raw_contact_id FROM emergency_added ORDER BY raw_contact_id LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    fun deleteAddedRecords(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.transaction {
            val placeholders = ids.joinToString(",") { "?" }
            val args = ids.map { it.toString() }.toTypedArray()
            delete("added_numbers", "raw_contact_id IN ($placeholders)", args)
            incrementCounter(this, "total_deleted", ids.size.toLong())
        }
    }

    fun deleteEmergencyRecords(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.transaction {
            val placeholders = ids.joinToString(",") { "?" }
            val args = ids.map { it.toString() }.toTypedArray()
            delete("emergency_added", "raw_contact_id IN ($placeholders)", args)
            incrementCounter(this, "total_deleted", ids.size.toLong())
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
            mobileGenerated = values["mobile_generated"] ?: 0,
            fixedGenerated = values["fixed_generated"] ?: 0,
            emergencyAdded = countEmergencyAdded(),
        )
    }

    fun resetStats() {
        writableDatabase.transaction {
            delete("sessions", null, null)
            execSQL(
                """
                UPDATE counters SET value = 0
                WHERE key IN (
                    'total_generated','total_inserted','total_deleted','total_sessions',
                    'last_rate_milli','mobile_generated','fixed_generated','emergency_added'
                )
                """.trimIndent()
            )
        }
    }

    fun prepareExistingNumberLookup() {
        val db = writableDatabase
        db.execSQL("CREATE TABLE IF NOT EXISTS existing_numbers(number TEXT PRIMARY KEY)")
        db.delete("existing_numbers", null, null)
    }

    fun populateExistingNumberLookup(
        rows: Sequence<String>,
        onProgress: (scanned: Long) -> Unit = {},
    ): Long {
        var scanned = 0L
        return writableDatabase.transaction {
            val stmt = compileStatement("INSERT OR IGNORE INTO existing_numbers(number) VALUES(?)")
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
            val found = rawQuery("SELECT COUNT(*) FROM existing_numbers", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            onProgress(scanned)
            found
        }
    }

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
     * Rebuilds ownership tables from contact notes. N1 and N2 rows are
     * generated numbers; E1 rows are emergency contacts and stay separate.
     */
    fun restoreAddedContacts(
        rows: Sequence<OwnedContact>,
        onProgress: (scanned: Long) -> Unit = {},
    ): RestoreResult {
        var scanned = 0L
        var emergency = 0L
        val now = System.currentTimeMillis()
        return writableDatabase.transaction {
            delete("added_numbers", null, null)
            delete("emergency_added", null, null)
            val generatedStmt = compileStatement(
                """
                INSERT OR REPLACE INTO added_numbers(
                    raw_contact_id, number, session_id, created_at, e164, iso2, line_type, kind
                ) VALUES(?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            val emergencyStmt = compileStatement(
                """
                INSERT OR REPLACE INTO emergency_added(
                    raw_contact_id, number, iso2, category, label, created_at
                ) VALUES(?,?,?,?,?,?)
                """.trimIndent()
            )
            try {
                rows.forEach { row ->
                    when (val tag = row.tag) {
                        is OwnershipTag.N1 -> {
                            val local = tag.localNumber
                            generatedStmt.bindLong(1, row.rawContactId)
                            generatedStmt.bindString(2, local)
                            generatedStmt.bindNull(3)
                            generatedStmt.bindLong(4, now)
                            generatedStmt.bindString(5, SyrianNumbers.toE164(local) ?: "")
                            generatedStmt.bindString(6, "SY")
                            generatedStmt.bindString(7, LineType.MOBILE.code)
                            generatedStmt.bindString(8, KIND_GENERATED)
                            generatedStmt.executeInsert()
                            scanned++
                        }
                        is OwnershipTag.N2 -> {
                            generatedStmt.bindLong(1, row.rawContactId)
                            generatedStmt.bindString(2, tag.e164)
                            generatedStmt.bindNull(3)
                            generatedStmt.bindLong(4, now)
                            generatedStmt.bindString(5, tag.e164)
                            generatedStmt.bindString(6, tag.iso2)
                            generatedStmt.bindString(7, tag.lineType.code)
                            generatedStmt.bindString(8, KIND_GENERATED)
                            generatedStmt.executeInsert()
                            scanned++
                        }
                        is OwnershipTag.E1 -> {
                            emergencyStmt.bindLong(1, row.rawContactId)
                            emergencyStmt.bindString(2, tag.number)
                            emergencyStmt.bindString(3, tag.iso2)
                            emergencyStmt.bindString(4, tag.category)
                            emergencyStmt.bindString(5, tag.label)
                            emergencyStmt.bindLong(6, now)
                            emergencyStmt.executeInsert()
                            emergency++
                        }
                    }
                    if ((scanned + emergency) % 1_000 == 0L) onProgress(scanned + emergency)
                }
            } finally {
                generatedStmt.close()
                emergencyStmt.close()
            }
            val found = rawQuery("SELECT COUNT(*) FROM added_numbers", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            val emergencyFound = rawQuery("SELECT COUNT(*) FROM emergency_added", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
            val mobileFound = rawQuery(
                "SELECT COUNT(*) FROM added_numbers WHERE line_type = ?",
                arrayOf(ActualLineType.MOBILE.code),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            val fixedFound = rawQuery(
                "SELECT COUNT(*) FROM added_numbers WHERE line_type = ?",
                arrayOf(ActualLineType.FIXED.code),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            val last = rawQuery("SELECT MAX(number) FROM added_numbers", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
            var lastE164: String? = null
            var lastIso2: String? = null
            var lastLineType: String? = null
            rawQuery(
                "SELECT e164, iso2, line_type FROM added_numbers " +
                    "ORDER BY raw_contact_id DESC LIMIT 1",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) lastE164 = cursor.getString(0)
                    if (!cursor.isNull(1)) lastIso2 = cursor.getString(1)
                    if (!cursor.isNull(2)) lastLineType = cursor.getString(2)
                }
            }
            if (found > getCounter(this, "total_generated")) {
                setCounter(this, "total_generated", found)
            }
            if (found > getCounter(this, "total_inserted")) {
                setCounter(this, "total_inserted", found)
            }
            if (mobileFound > getCounter(this, "mobile_generated")) {
                setCounter(this, "mobile_generated", mobileFound)
            }
            if (fixedFound > getCounter(this, "fixed_generated")) {
                setCounter(this, "fixed_generated", fixedFound)
            }
            if (emergencyFound > getCounter(this, "emergency_added")) {
                setCounter(this, "emergency_added", emergencyFound)
            }
            onProgress(scanned + emergency)
            RestoreResult(found, last, emergencyFound, lastE164, lastIso2, lastLineType)
        }
    }

    fun exportCursor(): Cursor =
        readableDatabase.rawQuery(
            "SELECT number, e164, iso2, line_type, kind FROM added_numbers ORDER BY raw_contact_id",
            null,
        )

    fun emergencyExportCursor(): Cursor =
        readableDatabase.rawQuery(
            "SELECT number, iso2, category, label FROM emergency_added ORDER BY raw_contact_id",
            null,
        )

    fun saveCursor(iso2: String, lineType: LineType, lastNsn: String) {
        writableDatabase.execSQL(
            """
            INSERT INTO country_cursors(iso2, line_type, last_nsn, updated_at)
            VALUES(?,?,?,?)
            ON CONFLICT(iso2, line_type) DO UPDATE SET
                last_nsn = excluded.last_nsn,
                updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf<Any>(iso2, lineType.code, lastNsn, System.currentTimeMillis()),
        )
    }

    fun loadCursor(iso2: String, lineType: LineType): String? =
        readableDatabase.rawQuery(
            "SELECT last_nsn FROM country_cursors WHERE iso2 = ? AND line_type = ?",
            arrayOf(iso2, lineType.code),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

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
        private const val DB_VERSION = 3

        const val KIND_GENERATED = "GENERATED"
    }
}
