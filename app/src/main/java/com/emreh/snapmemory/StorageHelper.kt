package com.emreh.snapmemory
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

object StorageHelper {
    private const val TAG = "EmRecall.Storage"
    private const val MAX_WIDTH = 720
    private const val QUALITY = 58
    private const val PRIVATE_PREFIX = "private://"
    data class Saved(val timestamp: Long, val reference: String)

    fun save(context: Context, bitmap: Bitmap, timestamp: Long, packageName: String): Saved? {
        val normalized = downscale(bitmap)
        return try {
            saveToSelectedFolder(context, normalized, timestamp, packageName) ?: saveToPrivateStorage(context, normalized, timestamp, packageName)
        } finally {
            if (normalized !== bitmap) normalized.recycle()
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_WIDTH) return bitmap
        val scale = MAX_WIDTH.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun saveToSelectedFolder(context: Context, bitmap: Bitmap, timestamp: Long, packageName: String): Saved? {
        val uriText = Prefs.folderUri(context) ?: return null
        return runCatching {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriText))?.takeIf { it.canWrite() } ?: return null
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(timestamp))
            val dayDir = tree.findFile(day) ?: tree.createDirectory(day) ?: return null
            val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val file = dayDir.createFile("image/webp", "${timestamp}_${safePackage}.webp") ?: return null
            val wrote = context.contentResolver.openOutputStream(file.uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out) } == true
            if (!wrote) { file.delete(); null } else Saved(timestamp, file.uri.toString())
        }.onFailure { Log.w(TAG, "SAF save failed", it) }.getOrNull()
    }

    private fun saveToPrivateStorage(context: Context, bitmap: Bitmap, timestamp: Long, packageName: String): Saved? {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(timestamp))
        val dir = File(context.filesDir, "screenshots/$day")
        if (!dir.exists() && !dir.mkdirs()) { Log.w(TAG, "Unable to create " + dir); return null }
        val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val file = File(dir, "${timestamp}_${safePackage}.webp")
        return try {
            FileOutputStream(file).use { out -> if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)) { file.delete(); return null } }
            Saved(timestamp, PRIVATE_PREFIX + day + "/" + file.name)
        } catch (t: Throwable) {
            file.delete()
            Log.w(TAG, "Private save failed", t)
            null
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