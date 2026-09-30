package com.emreh.snapmemory

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OcrIndexer(
    private val db: MemoryDb
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun indexPending(
        maxItems: Int = 120,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        executor.execute {
            val rows = db.pendingOcr(maxItems)
            if (rows.isEmpty()) {
                onProgress(0, 0)
                onDone()
                return@execute
            }
            process(rows, 0, onProgress, onDone)
        }
    }

    private fun process(
        rows: List<MemoryDb.Row>,
        index: Int,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        if (index >= rows.size) {
            onDone()
            return
        }

        val row = rows[index]
        val bitmap = BitmapFactory.decodeFile(row.path)
        if (bitmap == null) {
            onProgress(index + 1, rows.size)
            executor.execute {
                process(rows, index + 1, onProgress, onDone)
            }
            return
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener(executor) { result ->
                db.updateOcr(row.id, result.text.trim())
                bitmap.recycle()
                onProgress(index + 1, rows.size)
                process(rows, index + 1, onProgress, onDone)
            }
            .addOnFailureListener(executor) {
                bitmap.recycle()
                onProgress(index + 1, rows.size)
                process(rows, index + 1, onProgress, onDone)
            }
    }

    fun close() {
        recognizer.close()
        executor.shutdownNow()
    }
}
