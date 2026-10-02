package com.emreh.snapmemory

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val captureBusy = AtomicBoolean(false)
    private lateinit var db: MemoryDb
    @Volatile private var foregroundPackage = "unknown"
    @Volatile private var forceNextCapture = true
    @Volatile private var nextCaptureDelayMs = DEFAULT_INTERVAL_MS
    private var lastCapturePrefAt = 0L
    private var lastErrorPref = ""
    private val changeDetector = ChangeDetector()
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(captureRunnable)
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(captureRunnable)
                    handler.post(captureRunnable)
                }
            }
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            val usable = isScreenUsable()
            if (usable && Prefs.enabled(this@ScreenCaptureAccessibilityService)) captureOnce()
            if (usable) handler.postDelayed(this, nextCaptureDelayMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = MemoryDb.get(this)
        lastCapturePrefAt = Prefs.lastCaptureAt(this)
        lastErrorPref = Prefs.lastError(this)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshForegroundPackage()
        handler.removeCallbacks(captureRunnable)
        if (isScreenUsable()) handler.post(captureRunnable)
        enforceRetention()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshForegroundPackage()
        }
    }

    private fun refreshForegroundPackage() {
        runCatching {
            val active = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                ?: return@runCatching
            val pkg = active.root?.packageName?.toString() ?: return@runCatching
            if (pkg != foregroundPackage) {
                forceNextCapture = true
                nextCaptureDelayMs = ACTIVE_INTERVAL_MS
            }
            foregroundPackage = pkg
        }.onFailure {
            Log.w(TAG, "Unable to resolve foreground application window", it)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        flushPrefs()
        runCatching { unregisterReceiver(screenReceiver) }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun captureOnce() {
        if (!isScreenUsable() || !captureBusy.compareAndSet(false, true)) return
        val targetPackage = foregroundPackage
        if (targetPackage == "unknown" || targetPackage == packageName || isExcluded(targetPackage)) {
            captureBusy.set(false)
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        worker.execute { processScreenshot(screenshot, targetPackage) }
                    } catch (e: RejectedExecutionException) {
                        failCapture("Capture worker rejected", e)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    failCapture("Screenshot failed: " + errorCode, null)
                }
            })
        } catch (t: Throwable) {
            failCapture("Screenshot request failed", t)
        }
    }

    private fun processScreenshot(screenshot: ScreenshotResult, targetPackage: String) {
        var bitmap: Bitmap? = null
        var hardwareBitmap: Bitmap? = null
        var probe = IntArray(0)
        try {
            val buffer = screenshot.hardwareBuffer
            try {
                hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                val fullBitmap = hardwareBitmap ?: run {
                    failCapture("Could not create bitmap", null)
                    return
                }

                // The change detector operates directly on the hardware-backed bitmap.
                // Only changed candidates are copied into a CPU bitmap for encoding.
                probe = buildProbe(fullBitmap)
                if (!changeDetector.shouldCapture(probe, forceNextCapture)) {
                    backoffCapture()
                    return
                }

                bitmap = fullBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                buffer.close()
            }

            val saveBitmap = bitmap ?: run {
                failCapture("Could not create CPU bitmap", null)
                return
            }

            val now = System.currentTimeMillis()
            val saved = StorageHelper.save(this, saveBitmap, now, targetPackage) ?: run {
                failCapture("Screenshot could not be saved", null)
                return
            }

            try {
                val label = appLabel(targetPackage)
                db.insert(saved.timestamp, targetPackage, label, saved.reference)
                changeDetector.markSaved(probe)
                forceNextCapture = false
                backoffCapture(reset = true)
                setLastCaptureCached(now)
                clearLastErrorCached()
            } catch (t: Throwable) {
                StorageHelper.delete(this, saved.reference)
                failCapture("Database insert failed; file removed", t)
            }
            enforceRetention()
        } finally {
            bitmap?.recycle()
            hardwareBitmap?.recycle()
            captureBusy.set(false)
        }
    }

    private fun buildProbe(bitmap: Bitmap): IntArray {
        val cols = PROBE_COLS
        val rows = PROBE_ROWS
        val out = IntArray(cols * rows)
        val xStep = bitmap.width.toFloat() / cols
        val yStep = bitmap.height.toFloat() / rows

        for (y in 0 until rows) {
            val y0 = (y * yStep).toInt().coerceIn(0, bitmap.height - 1)
            val y1 = ((y + 1) * yStep).toInt().coerceIn(y0 + 1, bitmap.height)
            for (x in 0 until cols) {
                val x0 = (x * xStep).toInt().coerceIn(0, bitmap.width - 1)
                val x1 = ((x + 1) * xStep).toInt().coerceIn(x0 + 1, bitmap.width)
                val sx = maxOf(1, (x1 - x0) / PROBE_SAMPLES_PER_CELL)
                val sy = maxOf(1, (y1 - y0) / PROBE_SAMPLES_PER_CELL)
                var sum = 0L
                var count = 0
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

    private fun backoffCapture(reset: Boolean = false) {
        nextCaptureDelayMs = if (reset) ACTIVE_INTERVAL_MS else {
            minOf(MAX_INTERVAL_MS, nextCaptureDelayMs * 2)
        }
    }

    private fun setLastCaptureCached(value: Long) {
        val previous = lastCapturePrefAt
        lastCapturePrefAt = value
        if (previous == 0L || value - previous >= PREF_FLUSH_INTERVAL_MS) flushLastCapture()
    }

    private fun clearLastErrorCached() {
        if (lastErrorPref.isNotEmpty()) {
            lastErrorPref = ""
            Prefs.setLastError(this, "")
        }
    }

    private fun failCapture(message: String, error: Throwable?) {
        captureBusy.set(false)
        if (message != lastErrorPref) {
            lastErrorPref = message
            Prefs.setLastError(this, message)
        }
        Log.w(TAG, message, error)
    }

    private fun flushLastCapture() {
        if (lastCapturePrefAt != 0L) Prefs.setLastCaptureAt(this, lastCapturePrefAt)
    }

    private fun flushPrefs() {
        flushLastCapture()
    }

    private fun enforceRetention() {
        val days = Prefs.retentionDays(this)
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        db.referencesOlderThan(cutoff).forEach { ref ->
            if (!StorageHelper.exists(this, ref.path) || StorageHelper.delete(this, ref.path)) {
                db.delete(ref.id)
            }
        }
    }

    private fun isExcluded(pkg: String): Boolean {
        if (Prefs.excluded(this).contains(pkg)) return true
        val text = (pkg + " " + appLabel(pkg)).lowercase()
        return listOf(
            "bank", "banking", "upi", "wallet", "authenticator", "otp",
            "password", "passkey", "vault", "payments", "payment"
        ).any(text::contains)
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrElse { pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() } }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        flushPrefs()
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "EmRecall.Capture"
        private const val PROBE_COLS = 30
        private const val PROBE_ROWS = 18
        private const val PROBE_SAMPLES_PER_CELL = 4
        private const val DEFAULT_INTERVAL_MS = 15_000L
        private const val ACTIVE_INTERVAL_MS = 10_000L
        private const val MAX_INTERVAL_MS = 30_000L
        private const val PREF_FLUSH_INTERVAL_MS = 60_000L
    }
}
