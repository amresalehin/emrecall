package com.emreh.snapmemory

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ScreenCaptureAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val captureBusy = AtomicBoolean(false)
    private lateinit var db: MemoryDb

    @Volatile private var foregroundPackage = "unknown"
    @Volatile private var forceNextCapture = true
    @Volatile private var lastSavedProbe: IntArray? = null
    @Volatile private var lastCaptureAt = 0L
    @Volatile private var lastError = ""

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (Prefs.enabled(this@ScreenCaptureAccessibilityService) && isScreenUsable()) captureOnce()
            handler.postDelayed(this, Prefs.interval(this@ScreenCaptureAccessibilityService) * 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = MemoryDb.get(this)
        handler.removeCallbacks(captureRunnable)
        handler.post(captureRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod", true) || pkg.contains("keyboard", true)) return
        if (foregroundPackage != pkg) forceNextCapture = true
        foregroundPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun captureOnce() {
        if (!isScreenUsable() || !captureBusy.compareAndSet(false, true)) return
        val targetPackage = foregroundPackage
        if (targetPackage == "unknown" || targetPackage == packageName || Prefs.excluded(this).contains(targetPackage)) {
            captureBusy.set(false)
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        worker.execute { processScreenshot(screenshot, targetPackage) }
                    } catch (e: RejectedExecutionException) {
                        captureBusy.set(false)
                        lastError = "Capture worker rejected"
                        Log.w(TAG, lastError, e)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    captureBusy.set(false)
                    lastError = "Screenshot failed: $errorCode"
                    Log.w(TAG, lastError)
                }
            })
        } catch (t: Throwable) {
            captureBusy.set(false)
            lastError = "Screenshot request failed"
            Log.w(TAG, lastError, t)
        }
    }

    private fun processScreenshot(screenshot: ScreenshotResult, targetPackage: String) {
        var bitmap: Bitmap? = null
        try {
            val buffer = screenshot.hardwareBuffer
            try {
                bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                buffer.close()
            }
            val fullBitmap = bitmap ?: return
            val probe = buildProbe(fullBitmap)
            if (!forceNextCapture && !changedEnough(probe)) return
            val now = System.currentTimeMillis()
            val saved = StorageHelper.save(this, fullBitmap, now, targetPackage) ?: run {
                lastError = "Screenshot could not be saved"
                Log.w(TAG, lastError)
                return
            }
            try {
                db.insert(saved.timestamp, targetPackage, saved.reference)
                lastSavedProbe = probe
                forceNextCapture = false
                lastCaptureAt = now
                lastError = ""
            } catch (t: Throwable) {
                deleteReference(saved.reference)
                lastError = "Database insert failed; file removed"
                Log.w(TAG, lastError, t)
            }
            enforceRetention()
        } finally {
            bitmap?.recycle()
            captureBusy.set(false)
        }
    }

    private fun buildProbe(bitmap: Bitmap): IntArray {
        val cols = 60
        val rows = 34
        val out = IntArray(cols * rows)
        val xStep = bitmap.width.toFloat() / cols
        val yStep = bitmap.height.toFloat() / rows
        for (y in 0 until rows) {
            val y0 = (y * yStep).toInt().coerceIn(0, bitmap.height - 1)
            val y1 = ((y + 1) * yStep).toInt().coerceIn(y0 + 1, bitmap.height)
            for (x in 0 until cols) {
                val x0 = (x * xStep).toInt().coerceIn(0, bitmap.width - 1)
                val x1 = ((x + 1) * xStep).toInt().coerceIn(x0 + 1, bitmap.width)
                var sum = 0L
                var count = 0
                val sx = maxOf(1, (x1 - x0) / 8)
                val sy = maxOf(1, (y1 - y0) / 8)
                var yy = y0
                while (yy < y1) {
                    var xx = x0
                    while (xx < x1) {
                        val c = bitmap.getPixel(xx, yy)
                        sum += Color.red(c) * 3L + Color.green(c) * 6L + Color.blue(c)
                        count++
                        xx += sx
                    }
                    yy += sy
                }
                out[y * cols + x] = (sum / maxOf(1, count) / 10L).toInt()
            }
        }
        return out
    }

    private fun changedEnough(probe: IntArray): Boolean {
        val previous = lastSavedProbe ?: return true
        var changedCells = 0
        var totalDelta = 0L
        for (i in probe.indices) {
            val delta = abs(probe[i] - previous[i])
            totalDelta += delta
            if (delta >= 9) changedCells++
        }
        return changedCells.toDouble() / probe.size >= 0.08 || totalDelta.toDouble() / probe.size >= 5.0
    }

    private fun enforceRetention() {
        val days = Prefs.retentionDays(this)
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        db.cleanupOlderThan(cutoff).forEach(::deleteReference)
    }

    private fun deleteReference(reference: String) {
        runCatching {
            if (reference.startsWith("content://")) contentResolver.delete(android.net.Uri.parse(reference), null, null)
            else java.io.File(reference).delete()
        }.onFailure { Log.w(TAG, "Failed to delete $reference", it) }
    }

    private fun isScreenUsable() = powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    companion object { private const val TAG = "EmRecall.Capture" }
}