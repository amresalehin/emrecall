package com.emreh.snapmemory

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

class ScreenCaptureAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var db: MemoryDb

    @Volatile
    private var foregroundPackage = "unknown"

    private var lastProbe: IntArray? = null
    private var captureBusy = false
    private var captureCount = 0

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (Prefs.enabled(this@ScreenCaptureAccessibilityService)) {
                captureOnce()
            }
            handler.postDelayed(
                this,
                Prefs.interval(this@ScreenCaptureAccessibilityService) * 1000L
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = MemoryDb(this)
        handler.removeCallbacks(captureRunnable)
        handler.post(captureRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        foregroundPackage = event?.packageName?.toString() ?: return
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        if (::db.isInitialized) db.close()
        super.onDestroy()
    }

    private fun captureOnce() {
        if (captureBusy) return

        val targetPackage = foregroundPackage
        if (targetPackage == "unknown") return
        if (Prefs.excluded(this).contains(targetPackage)) return
        if (targetPackage == packageName) return

        captureBusy = true

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            worker,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    worker.execute {
                        var bitmap: Bitmap? = null
                        try {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                bitmap = Bitmap.wrapHardwareBuffer(
                                    buffer,
                                    screenshot.colorSpace
                                )?.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                buffer.close()
                            }

                            val fullBitmap = bitmap ?: return@execute
                            if (!changedEnough(fullBitmap)) return@execute

                            val saved = StorageHelper.save(
                                this@ScreenCaptureAccessibilityService,
                                fullBitmap,
                                System.currentTimeMillis(),
                                targetPackage
                            )
                            if (saved != null) {
                                db.insert(
                                    saved.first,
                                    targetPackage,
                                    saved.second
                                )
                                captureCount++
                                
                            }
                        } finally {
                            bitmap?.recycle()
                            captureBusy = false
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    captureBusy = false
                }
            }
        )
    }

    private fun changedEnough(bitmap: Bitmap): Boolean {
        val width = 48
        val height = 27
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        small.recycle()

        val probe = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            probe[i] =
                (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
        }

        val previous = lastProbe
        lastProbe = probe
        if (previous == null) return true

        var delta = 0L
        for (i in probe.indices) {
            delta += abs(probe[i] - previous[i])
        }

        val mean = delta.toDouble() / probe.size
        return mean >= 3.5
    }

    private fun saveBitmap(bitmap: Bitmap, now: Long): Pair<Long, String>? {
        val parent = getExternalFilesDir("snapshots") ?: return null
        val dir = File(parent, "screens")
        if (!dir.exists() && !dir.mkdirs()) return null

        val maxWidth = 720
        val output = if (bitmap.width > maxWidth) {
            val scale = maxWidth.toFloat() / bitmap.width
            Bitmap.createScaledBitmap(
                bitmap,
                maxWidth,
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }

        val file = File(dir, "$now.webp")
        return runCatching {
            val written = FileOutputStream(file).use { stream ->
                output.compress(Bitmap.CompressFormat.WEBP_LOSSY, 58, stream)
            }
            if (!written) {
                file.delete()
                return@runCatching null
            }
            if (output !== bitmap) output.recycle()
            now to file.absolutePath
        }.getOrNull()
    }
}
