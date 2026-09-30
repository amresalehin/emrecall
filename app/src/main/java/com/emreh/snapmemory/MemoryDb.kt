package com.emreh.snapmemory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class MemoryDb(context: Context) : SQLiteOpenHelper(context, "memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE snapshots(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                path TEXT NOT NULL,
                ocr_text TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_snapshots_time ON snapshots(captured_at DESC)")
        db.execSQL("CREATE INDEX idx_snapshots_package ON snapshots(package_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(capturedAt: Long, packageName: String, path: String, ocrText: String = "") {
        writableDatabase.execSQL(
            "INSERT INTO snapshots(captured_at, package_name, path, ocr_text) VALUES(?,?,?,?)",
            arrayOf(capturedAt, packageName, path, ocrText)
        )
    }

    data class Row(
        val id: Long,
        val capturedAt: Long,
        val packageName: String,
        val path: String,
        val ocrText: String
    )

    fun search(query: String, limit: Int = 80): List<Row> {
        val out = ArrayList<Row>()
        val sql = if (query.isBlank()) {
            "SELECT id,captured_at,package_name,path,ocr_text FROM snapshots ORDER BY captured_at DESC LIMIT ?"
        } else {
            "SELECT id,captured_at,package_name,path,ocr_text FROM snapshots " +
                "WHERE package_name LIKE ? OR ocr_text LIKE ? ORDER BY captured_at DESC LIMIT ?"
        }

        val args = if (query.isBlank()) {
            arrayOf(limit.toString())
        } else {
            arrayOf("%$query%", "%$query%", limit.toString())
        }

        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += Row(
                    id = c.getLong(0),
                    capturedAt = c.getLong(1),
                    packageName = c.getString(2),
                    path = c.getString(3),
                    ocrText = c.getString(4)
                )
            }
        }
        return out
    }

    fun pendingOcr(limit: Int = 120): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT id,captured_at,package_name,path,ocr_text " +
                "FROM snapshots WHERE ocr_text = '' ORDER BY captured_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Row(
                    c.getLong(0),
                    c.getLong(1),
                    c.getString(2),
                    c.getString(3),
                    c.getString(4)
                )
            }
        }
        return out
    }

    fun updateOcr(id: Long, text: String) {
        writableDatabase.execSQL(
            "UPDATE snapshots SET ocr_text = ? WHERE id = ?",
            arrayOf(text, id)
        )
    }

    fun deleteOlderThan(cutoff: Long) {
        val paths = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT path FROM snapshots WHERE captured_at < ?",
            arrayOf(cutoff.toString())
        ).use { c ->
            while (c.moveToNext()) paths += c.getString(0)
        }

        writableDatabase.delete(
            "snapshots",
            "captured_at < ?",
            arrayOf(cutoff.toString())
        )

        paths.forEach { runCatching { File(it).delete() } }
    }

    fun count(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
}
