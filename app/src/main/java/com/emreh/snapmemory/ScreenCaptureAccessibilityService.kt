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
            if (Prefs.enabled(this@ScreenCaptureAccessibilityService) && isScreenUsable()) captureOnce()
            if (isScreenUsable()) handler.postDelayed(this, Prefs.interval(this@ScreenCaptureAccessibilityService) * 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = MemoryDb.get(this)
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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshForegroundPackage()
        }
    }

    private fun refreshForegroundPackage() {
        runCatching {
            val active = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                ?: return@runCatching
            val pkg = active.root?.packageName?.toString() ?: return@runCatching
            if (pkg != foregroundPackage) forceNextCapture = true
            foregroundPackage = pkg
        }.onFailure {
            Log.w(TAG, "Unable to resolve foreground application window", it)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
        try {
            val buffer = screenshot.hardwareBuffer
            try {
                hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                buffer.close()
                hardwareBitmap?.recycle()
            }
            val fullBitmap = bitmap ?: run { failCapture("Could not create bitmap", null); return }
            val probe = buildProbe(fullBitmap)
            if (!changeDetector.shouldCapture(probe, forceNextCapture)) return
            val now = System.currentTimeMillis()
            val saved = StorageHelper.save(this, fullBitmap, now, targetPackage) ?: run {
                failCapture("Screenshot could not be saved", null)
                return
            }
            try {
                val label = appLabel(targetPackage)
                db.insert(saved.timestamp, targetPackage, label, saved.reference)
                changeDetector.markSaved(probe)
                forceNextCapture = false
                Prefs.setLastCaptureAt(this, now)
                Prefs.setLastError(this, "")
            } catch (t: Throwable) {
                StorageHelper.delete(this, saved.reference)
                failCapture("Database insert failed; file removed", t)
            }
            enforceRetention()
        } finally {
            bitmap?.recycle()
            captureBusy.set(false)
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrElse { pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() } }

    private fun isExcluded(pkg: String): Boolean {
        if (Prefs.excluded(this).contains(pkg)) return true
        val text = (pkg + " " + appLabel(pkg)).lowercase()
        return listOf("bank", "banking", "upi", "wallet", "authenticator", "otp", "password", "passkey", "vault", "payments", "payment").any(text::contains)
    }

    private fun failCapture(message: String, error: Throwable?) {
        captureBusy.set(false)
        Prefs.setLastError(this, message)
        Log.w(TAG, message, error)
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

    private fun enforceRetention() {
        val days = Prefs.retentionDays(this)
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        db.cleanupOlderThan(cutoff).forEach { StorageHelper.delete(this, it) }
    }

    private fun isScreenUsable() = powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    companion object { private const val TAG = "EmRecall.Capture" }
}