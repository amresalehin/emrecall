package com.emreh.snapmemory

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OcrIndexer(private val context: Context, private val db: MemoryDb) {
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val running = AtomicBoolean(false)

    fun indexPending(
        maxItems: Int = 50,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val rows = db.pendingOcr(maxItems.coerceIn(1, MAX_BATCH))
                if (rows.isEmpty()) {
                    postUi { onProgress(0, 0); onDone() }
                    return@execute
                }
                process(rows, 0, onProgress, onDone)
            } catch (t: Throwable) {
                Log.w(TAG, "OCR batch failed", t)
                postUi { onDone() }
            } finally {
                running.set(false)
            }
        }
    }

    private fun process(
        rows: List<MemoryDb.Row>,
        index: Int,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        if (index >= rows.size) {
            postUi(onDone)
            return
        }

        val row = rows[index]
        if (!db.markOcrProcessing(row.id)) {
            postProgress(index + 1, rows.size, onProgress)
            process(rows, index + 1, onProgress, onDone)
            return
        }

        val bitmap = StorageHelper.open(context, row.path, OCR_MAX_WIDTH, OCR_MAX_HEIGHT)
        if (bitmap == null) {
            db.markOcrFailed(row.id, "Image could not be opened")
            postProgress(index + 1, rows.size, onProgress)
            process(rows, index + 1, onProgress, onDone)
            return
        }

        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(executor) { result ->
                    try {
                        db.updateOcr(row.id, result.text.trim())
                    } finally {
                        bitmap.recycle()
                        postProgress(index + 1, rows.size, onProgress)
                        process(rows, index + 1, onProgress, onDone)
                    }
                }
                .addOnFailureListener(executor) { error ->
                    try {
                        Log.w(TAG, "OCR failed for " + row.id, error)
                        db.markOcrFailed(row.id, error.message ?: "OCR failed")
                    } finally {
                        bitmap.recycle()
                        postProgress(index + 1, rows.size, onProgress)
                        process(rows, index + 1, onProgress, onDone)
                    }
                }
        } catch (t: Throwable) {
            bitmap.recycle()
            db.markOcrFailed(row.id, t.message ?: "OCR failed")
            postProgress(index + 1, rows.size, onProgress)
            process(rows, index + 1, onProgress, onDone)
        }
    }

    private fun postUi(action: () -> Unit) {
        (context as? MainActivity)?.runOnUiThread(action)
    }

    private fun postProgress(done: Int, total: Int, callback: (Int, Int) -> Unit) {
        postUi { callback(done, total) }
    }

    fun close() {
        running.set(false)
        recognizer.close()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "EmRecall.OCR"
        private const val MAX_BATCH = 50
        private const val OCR_MAX_WIDTH = 960
        private const val OCR_MAX_HEIGHT = 1440
    }
}