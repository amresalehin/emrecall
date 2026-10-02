package com.emreh.snapmemory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object StorageHelper {
    private const val TAG = "EmRecall.Storage"
    private const val MAX_WIDTH = 720
    private const val QUALITY = 92
    private const val PRIVATE_PREFIX = "private://"

    @Volatile private var cachedTreeUri: String? = null
    @Volatile private var cachedDay: String? = null
    @Volatile private var cachedDayDir: DocumentFile? = null

    data class Saved(val timestamp: Long, val reference: String)

    fun save(context: Context, bitmap: Bitmap, timestamp: Long, packageName: String): Saved? {
        val normalized = downscale(bitmap)
        return try {
            val folderUri = Prefs.folderUri(context)
            if (folderUri != null) {
                saveToSelectedFolder(context, normalized, timestamp, packageName, folderUri)
            } else {
                saveToPrivateStorage(context, normalized, timestamp, packageName)
            }
        } finally {
            if (normalized !== bitmap) normalized.recycle()
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_WIDTH) return bitmap
        val scale = MAX_WIDTH.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun saveToSelectedFolder(context: Context, bitmap: Bitmap, timestamp: Long, packageName: String, uriText: String): Saved? {
        return runCatching {
            val tree = getCachedTree(context, uriText) ?: throw IllegalStateException("Selected screenshot folder is unavailable")
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(timestamp))
            val dayDir = getCachedDayDir(tree, uriText, day) ?: throw IllegalStateException("Unable to access screenshot day directory")
            val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val fileName = timestamp.toString() + "_" + safePackage + ".webp"
            val file = dayDir.createFile("image/webp", fileName) ?: throw IllegalStateException("Unable to create screenshot file")
            try {
                val wrote = context.contentResolver.openOutputStream(file.uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out) } == true
                if (!wrote) { file.delete(); null } else Saved(timestamp, file.uri.toString())
            } catch (t: Throwable) { file.delete(); throw t }
        }.onFailure { Log.w(TAG, "SAF save failed", it) }.getOrNull()
    }

    private fun saveToPrivateStorage(context: Context, bitmap: Bitmap, timestamp: Long, packageName: String): Saved? {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(timestamp))
        val dir = File(context.filesDir, "screenshots/" + day)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create " + dir)
            return null
        }
        val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val file = File(dir, timestamp.toString() + "_" + safePackage + ".webp")
        return try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)) {
                    file.delete()
                    return null
                }
            }
            Saved(timestamp, PRIVATE_PREFIX + day + "/" + file.name)
        } catch (t: Throwable) {
            file.delete()
            Log.w(TAG, "Private save failed", t)
            null
        }
    }

    private fun getCachedTree(context: Context, uriText: String): DocumentFile? {
        if (cachedTreeUri != uriText) synchronized(this) {
            if (cachedTreeUri != uriText) { cachedTreeUri = uriText; cachedDay = null; cachedDayDir = null }
        }
        return DocumentFile.fromTreeUri(context, Uri.parse(uriText))?.takeIf { it.canWrite() }
    }

    private fun getCachedDayDir(tree: DocumentFile, uriText: String, day: String): DocumentFile? {
        if (cachedTreeUri == uriText && cachedDay == day && cachedDayDir?.canWrite() == true) return cachedDayDir
        synchronized(this) {
            if (cachedTreeUri == uriText && cachedDay == day && cachedDayDir?.canWrite() == true) return cachedDayDir
            val dir = tree.findFile(day) ?: tree.createDirectory(day)
            if (dir != null) { cachedTreeUri = uriText; cachedDay = day; cachedDayDir = dir }
            return dir
        }
    }

    fun open(context: Context, reference: String, maxWidth: Int = MAX_WIDTH, maxHeight: Int = Int.MAX_VALUE): Bitmap? = runCatching {
        when {
            reference.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(reference)).use { decodeSampled(it, maxWidth, maxHeight) }
            reference.startsWith(PRIVATE_PREFIX) -> decodeSampled(File(context.filesDir, "screenshots/" + reference.removePrefix(PRIVATE_PREFIX)), maxWidth, maxHeight)
            else -> decodeSampled(File(reference), maxWidth, maxHeight)
        }
    }.onFailure { Log.w(TAG, "Unable to open " + reference, it) }.getOrNull()

    fun exists(context: Context, reference: String): Boolean = runCatching {
        when {
            reference.startsWith("content://") -> context.contentResolver.openAssetFileDescriptor(Uri.parse(reference), "r")?.use { true } ?: false
            reference.startsWith(PRIVATE_PREFIX) -> File(context.filesDir, "screenshots/" + reference.removePrefix(PRIVATE_PREFIX)).isFile
            else -> File(reference).isFile
        }
    }.getOrDefault(false)

    fun delete(context: Context, reference: String): Boolean = runCatching {
        when {
            reference.startsWith("content://") -> context.contentResolver.delete(Uri.parse(reference), null, null) > 0
            reference.startsWith(PRIVATE_PREFIX) -> File(context.filesDir, "screenshots/" + reference.removePrefix(PRIVATE_PREFIX)).delete()
            else -> File(reference).delete()
        }
    }.onFailure { Log.w(TAG, "Unable to delete " + reference, it) }.getOrDefault(false)

    private fun decodeSampled(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = calculateSample(bounds, maxWidth, maxHeight); inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun decodeSampled(input: java.io.InputStream?, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (input == null) return null
        val bytes = input.use { it.readBytes() }
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = calculateSample(bounds, maxWidth, maxHeight); inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateSample(bounds: BitmapFactory.Options, maxWidth: Int, maxHeight: Int): Int {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1
        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2
        return sample
    }
}
