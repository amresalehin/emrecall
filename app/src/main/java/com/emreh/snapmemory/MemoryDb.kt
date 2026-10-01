package com.emreh.snapmemory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MemoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "memory.db", null, 3) {

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
                "path TEXT NOT NULL," +
                "ocr_text TEXT NOT NULL DEFAULT ''," +
                "ocr_done INTEGER NOT NULL DEFAULT 0)"
        )
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE snapshots ADD COLUMN ocr_done INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE snapshots SET ocr_done=CASE WHEN ocr_text<>'' THEN 1 ELSE 0 END")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_package_time ON snapshots(package_name,captured_at DESC)")
        }
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_time ON snapshots(captured_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_package ON snapshots(package_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_ocr_done ON snapshots(ocr_done,captured_at DESC)")
    }

    fun insert(capturedAt: Long, packageName: String, path: String) {
        writableDatabase.execSQL(
            "INSERT INTO snapshots(captured_at,package_name,path,ocr_text,ocr_done) VALUES(?,?,?,?,0)",
            arrayOf(capturedAt, packageName, path, "")
        )
    }

    data class Row(
        val id: Long,
        val capturedAt: Long,
        val packageName: String,
        val path: String,
        val ocrText: String,
        val ocrDone: Boolean
    )

    fun search(query: String, limit: Int = 300, fromInclusive: Long? = null): List<Row> {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (query.isNotBlank()) {
            val escaped = query.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            clauses += "(package_name LIKE ? ESCAPE '\\' COLLATE NOCASE OR ocr_text LIKE ? ESCAPE '\\' COLLATE NOCASE)"
            args += "%$escaped%"
            args += "%$escaped%"
        }
        if (fromInclusive != null) {
            clauses += "captured_at >= ?"
            args += fromInclusive.toString()
        }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val out = ArrayList<Row>()
        val sql = "SELECT id,captured_at,package_name,path,ocr_text,ocr_done FROM snapshots$where ORDER BY captured_at DESC LIMIT ?"
        args += limit.toString()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += Row(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) != 0)
        }
        return out
    }

    fun pendingOcr(limit: Int = 120): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT id,captured_at,package_name,path,ocr_text,ocr_done FROM snapshots WHERE ocr_done=0 ORDER BY captured_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += Row(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) != 0)
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
        val path = readableDatabase.rawQuery("SELECT path FROM snapshots WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }
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

    fun count(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun cleanupOlderThan(cutoff: Long): List<String> {
        val paths = ArrayList<String>()
        readableDatabase.rawQuery("SELECT path FROM snapshots WHERE captured_at < ?", arrayOf(cutoff.toString())).use { c ->
            while (c.moveToNext()) paths += c.getString(0)
        }
        writableDatabase.delete("snapshots", "captured_at < ?", arrayOf(cutoff.toString()))
        return paths
    }

    companion object {
        @Volatile private var INSTANCE: MemoryDb? = null
        fun get(context: Context): MemoryDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryDb(context.applicationContext).also { INSTANCE = it }
            }
    }
}
