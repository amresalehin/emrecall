package com.emreh.snapmemory

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class OcrIndexer(private val context: Context, private val db: MemoryDb) {
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun indexPending(maxItems: Int = 120, onProgress: (Int, Int) -> Unit, onDone: () -> Unit) {
        executor.execute {
            val rows = db.pendingOcr(maxItems)
            if (rows.isEmpty()) {
                (context as? MainActivity)?.runOnUiThread { onProgress(0, 0); onDone() }
                return@execute
            }
            process(rows, 0, onProgress, onDone)
        }
    }

    private fun process(rows: List<MemoryDb.Row>, index: Int, onProgress: (Int, Int) -> Unit, onDone: () -> Unit) {
        if (index >= rows.size) {
            (context as? MainActivity)?.runOnUiThread(onDone)
            return
        }
        val row = rows[index]
        val bitmap = StorageHelper.open(context, row.path, 1280, 1920)
        if (bitmap == null) {
            db.markOcrDone(row.id)
            next(rows, index, onProgress, onDone)
            return
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                db.updateOcr(row.id, result.text.trim())
                bitmap.recycle()
                next(rows, index, onProgress, onDone)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "OCR failed for " + row.id, error)
                db.markOcrDone(row.id)
                bitmap.recycle()
                next(rows, index, onProgress, onDone)
            }
    }

    private fun next(rows: List<MemoryDb.Row>, index: Int, onProgress: (Int, Int) -> Unit, onDone: () -> Unit) {
        (context as? MainActivity)?.runOnUiThread { onProgress(index + 1, rows.size) }
        executor.execute { process(rows, index + 1, onProgress, onDone) }
    }

    fun close() { recognizer.close(); executor.shutdownNow() }

    companion object { private const val TAG = "EmRecall.OCR" }
}