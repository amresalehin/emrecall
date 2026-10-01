package com.emreh.snapmemory

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

object StorageHelper {
    data class Saved(val timestamp: Long, val reference: String)

    fun save(context: Context, bitmap: android.graphics.Bitmap, timestamp: Long, packageName: String): Saved? {
        val uriText = Prefs.folderUri(context)
        if (uriText != null) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriText))
            if (tree != null && tree.canWrite()) {
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(timestamp))
                val dayDir = tree.findFile(day) ?: tree.createDirectory(day)
                if (dayDir != null) {
                    val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
                    val name = timestamp.toString() + "_" + safePackage + ".webp"
                    val file = dayDir.createFile("image/webp", name)
                    if (file != null) {
                        context.contentResolver.openOutputStream(file.uri)?.use { out ->
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 58, out)) return null
                        }
                        return Saved(timestamp, file.uri.toString())
                    }
                }
            }
        }
        val parent = context.getExternalFilesDir("screenshots") ?: return null
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(timestamp))
        val dir = File(parent, day)
        if (!dir.exists() && !dir.mkdirs()) return null
        val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val file = File(dir, timestamp.toString() + "_" + safePackage + ".webp")
        return runCatching {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 58, out)) {
                    file.delete()
                    return@runCatching null
                }
            }
            Saved(timestamp, file.absolutePath)
        }.getOrNull()
    }

    fun open(context: Context, reference: String): android.graphics.Bitmap? = runCatching {
        if (reference.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(reference)).use { android.graphics.BitmapFactory.decodeStream(it) }
        } else android.graphics.BitmapFactory.decodeFile(reference)
    }.getOrNull()
}
