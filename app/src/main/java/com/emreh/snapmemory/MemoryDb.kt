package com.emreh.snapmemory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MemoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "memory.db", null, 5) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE snapshots(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "captured_at INTEGER NOT NULL," +
                "package_name TEXT NOT NULL," +
                "app_label TEXT NOT NULL DEFAULT ''," +
                "path TEXT NOT NULL," +
                "ocr_text TEXT NOT NULL DEFAULT ''," +
                "ocr_done INTEGER NOT NULL DEFAULT 0)"
        )
        createIndexes(db)
        createFts(db)
        rebuildFts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE snapshots ADD COLUMN ocr_done INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE snapshots SET ocr_done=CASE WHEN ocr_text<>'' THEN 1 ELSE 0 END")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_package_time ON snapshots(package_name,captured_at DESC)")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE snapshots ADD COLUMN app_label TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 5) {
            createFts(db)
            rebuildFts(db)
        }
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_time ON snapshots(captured_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_package ON snapshots(package_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_app_label ON snapshots(app_label)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_ocr_done ON snapshots(ocr_done,captured_at DESC)")
    }

    private fun createFts(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS snapshots_fts USING fts4(" +
                "snapshot_id UNINDEXED,package_name,app_label,ocr_text)"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS snapshots_fts_ai AFTER INSERT ON snapshots BEGIN " +
                "INSERT INTO snapshots_fts(snapshot_id,package_name,app_label,ocr_text) " +
                "VALUES(new.id,new.package_name,new.app_label,new.ocr_text); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS snapshots_fts_ad AFTER DELETE ON snapshots BEGIN " +
                "DELETE FROM snapshots_fts WHERE snapshot_id=old.id; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS snapshots_fts_au AFTER UPDATE ON snapshots BEGIN " +
                "DELETE FROM snapshots_fts WHERE snapshot_id=old.id; " +
                "INSERT INTO snapshots_fts(snapshot_id,package_name,app_label,ocr_text) " +
                "VALUES(new.id,new.package_name,new.app_label,new.ocr_text); END"
        )
    }

    private fun rebuildFts(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM snapshots_fts")
        db.execSQL(
            "INSERT INTO snapshots_fts(snapshot_id,package_name,app_label,ocr_text) " +
                "SELECT id,package_name,app_label,ocr_text FROM snapshots"
        )
    }

    fun insert(capturedAt: Long, packageName: String, appLabel: String, path: String) {
        writableDatabase.execSQL(
            "INSERT INTO snapshots(captured_at,package_name,app_label,path,ocr_text,ocr_done) VALUES(?,?,?,?,?,0)",
            arrayOf(capturedAt, packageName, appLabel, path, "")
        )
    }

    fun updateAppLabel(id: Long, label: String) {
        writableDatabase.execSQL("UPDATE snapshots SET app_label=? WHERE id=?", arrayOf(label, id))
    }

    data class Row(
        val id: Long,
        val capturedAt: Long,
        val packageName: String,
        val appLabel: String,
        val path: String,
        val ocrText: String,
        val ocrDone: Boolean
    )

    data class Reference(val id: Long, val path: String)

    data class LabelRow(val id: Long, val packageName: String, val appLabel: String)

    private fun ftsQuery(query: String): String? {
        val tokens = query.trim().split(Regex("\\s+")).mapNotNull { token ->
            token.replace(Regex("[^\\p{L}\\p{N}_]"), "").takeIf { it.isNotEmpty() }
        }
        return tokens.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { it + "*" }
    }

    fun search(query: String, limit: Int = 300, fromInclusive: Long? = null): List<Row> {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        val match = if (query.isBlank()) null else ftsQuery(query) ?: return emptyList()

        if (match != null) {
            clauses += "s.id IN (SELECT snapshot_id FROM snapshots_fts WHERE snapshots_fts MATCH ?)"
            args += match
        }
        if (fromInclusive != null) {
            clauses += "s.captured_at >= ?"
            args += fromInclusive.toString()
        }

        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        args += limit.toString()
        val sql =
            "SELECT s.id,s.captured_at,s.package_name,s.app_label,s.path,s.ocr_text,s.ocr_done " +
                "FROM snapshots s$where ORDER BY s.captured_at DESC LIMIT ?"

        val out = ArrayList<Row>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += Row(
                c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getInt(6) != 0
            )
        }
        return out
    }

    fun count(query: String = "", fromInclusive: Long? = null): Long {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        val match = if (query.isBlank()) null else ftsQuery(query) ?: return 0L

        if (match != null) {
            clauses += "s.id IN (SELECT snapshot_id FROM snapshots_fts WHERE snapshots_fts MATCH ?)"
            args += match
        }
        if (fromInclusive != null) {
            clauses += "s.captured_at >= ?"
            args += fromInclusive.toString()
        }

        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM snapshots s$where",
            args.toTypedArray()
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    fun allReferences(): List<Reference> {
        val out = ArrayList<Reference>()
        readableDatabase.rawQuery("SELECT id,path FROM snapshots", null).use { c ->
            while (c.moveToNext()) out += Reference(c.getLong(0), c.getString(1))
        }
        return out
    }

    fun labelsNeedingBackfill(): List<LabelRow> {
        val out = ArrayList<LabelRow>()
        readableDatabase.rawQuery(
            "SELECT id,package_name,app_label FROM snapshots WHERE app_label=''",
            null
        ).use { c ->
            while (c.moveToNext()) out += LabelRow(c.getLong(0), c.getString(1), c.getString(2))
        }
        return out
    }

    fun pendingOcr(limit: Int = 120): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT id,captured_at,package_name,app_label,path,ocr_text,ocr_done " +
                "FROM snapshots WHERE ocr_done=0 ORDER BY captured_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += Row(
                c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getInt(6) != 0
            )
        }
        return out
    }

    fun updateOcr(id: Long, text: String) {
        writableDatabase.execSQL("UPDATE snapshots SET ocr_text=?,ocr_done=1 WHERE id=?", arrayOf(text, id))
    }

    fun markOcrDone(id: Long) {
        writableDatabase.execSQL("UPDATE snapshots SET ocr_done=1 WHERE id=?", arrayOf(id))
    }

    fun delete(id: Long): String? {
        val path = readableDatabase.rawQuery(
            "SELECT path FROM snapshots WHERE id=?",
            arrayOf(id.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        writableDatabase.delete("snapshots", "id=?", arrayOf(id.toString()))
        return path
    }

    fun clear(): List<String> {
        val paths = ArrayList<String>()
        readableDatabase.rawQuery("SELECT path FROM snapshots", null).use { c ->
            while (c.moveToNext()) paths += c.getString(0)
        }
        writableDatabase.delete("snapshots", null, null)
        return paths
    }

    fun referencesOlderThan(cutoff: Long): List<Reference> {
        val out = ArrayList<Reference>()
        readableDatabase.rawQuery(
            "SELECT id,path FROM snapshots WHERE captured_at < ? ORDER BY captured_at ASC",
            arrayOf(cutoff.toString())
        ).use { c -> while (c.moveToNext()) out += Reference(c.getLong(0), c.getString(1)) }
        return out
    }

    companion object {
        @Volatile private var INSTANCE: MemoryDb? = null
        fun get(context: Context): MemoryDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: MemoryDb(context.applicationContext).also { INSTANCE = it }
        }
    }
}
