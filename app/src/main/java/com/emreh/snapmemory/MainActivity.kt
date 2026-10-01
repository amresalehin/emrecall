package com.emreh.snapmemory

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : FragmentActivity() {
    private val db get() = MemoryDb.get(this)
    private lateinit var timeline: RecyclerView
    private lateinit var adapter: TimelineAdapter
    private lateinit var search: EditText
    private lateinit var scrubber: TimelineScrubberView
    private lateinit var status: TextView
    private var allMode = true
    private val io = Executors.newSingleThreadExecutor()
    private var ocrIndexer: OcrIndexer? = null
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            Prefs.setFolderUri(this, uri.toString())
            Toast.makeText(this, "Screenshot folder selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        configureSystemBars()
        buildUi()
        if (Prefs.appLock(this)) showBiometricGate()
    }

    override fun onResume() {
        super.onResume()
        if (::timeline.isInitialized) renderTimeline()
        maintainStorage()
    }

    override fun onDestroy() {
        ocrIndexer?.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun showBiometricGate() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finish()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock EmRecall")
            .setSubtitle("Authenticate to view your captured screen history")
            .setNegativeButtonText("Close")
            .build()
        prompt.authenticate(info)
    }

    private fun configureSystemBars() {
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp(), 12.dp(), 12.dp(), 10.dp())
        }
        toolbar.addView(TextView(this).apply {
            text = "EmRecall"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(MaterialButton(this).apply {
            text = "Settings"
            minHeight = 48.dp()
            setOnClickListener { showSettings() }
        })
        content.addView(toolbar)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 0, 16.dp(), 8.dp())
        }
        search = EditText(this).apply {
            hint = "Search memories"
            setSingleLine(true)
            setPadding(18.dp(), 0, 18.dp(), 0)
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, 54.dp(), 1f))
        searchRow.addView(MaterialButton(this).apply {
            text = "Search"
            minHeight = 48.dp()
            setOnClickListener { renderTimeline() }
        })
        content.addView(searchRow)

        val filters = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(16.dp(), 0, 16.dp(), 8.dp())
        }
        filters.addView(chip("All memories", true) {
            allMode = true
            renderTimeline()
        })
        filters.addView(chip("Today", false) {
            allMode = false
            renderTimeline(true)
        })
        content.addView(filters)

        val frame = FrameLayout(this)
        timeline = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setPadding(16.dp(), 48.dp(), 44.dp(), 40.dp())
            isVerticalScrollBarEnabled = false
            adapter = TimelineAdapter(
                this@MainActivity,
                onClick = { row ->
                    startActivity(
                        Intent(this@MainActivity, PreviewActivity::class.java)
                            .putExtra("path", row.path)
                    )
                },
                onLongClick = { row ->
                    deleteOne(row)
                    true
                },
                onMissing = { row ->
                    deleteOne(row, false)
                }
            ).also { adapter = it }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    updateScrubber()
                }
            })
        }
        frame.addView(timeline, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            textSize = 13f
            alpha = 0.72f
            setPadding(16.dp(), 2.dp(), 16.dp(), 8.dp())
        }
        frame.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        scrubber = TimelineScrubberView(this).apply {
            setOnPositionChanged { fraction ->
                if (adapter.itemCount > 0) {
                    (timeline.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(
                            (fraction * (adapter.itemCount - 1)).toInt(),
                            0
                        )
                }
            }
        }
        frame.addView(
            scrubber,
            FrameLayout.LayoutParams(38.dp(), -1, Gravity.END).apply {
                topMargin = 8.dp()
                bottomMargin = 12.dp()
                rightMargin = 2.dp()
            }
        )
        content.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        renderTimeline()
    }

    private fun renderTimeline(todayOnly: Boolean = !allMode) {
        val query = search.text?.toString()?.trim().orEmpty()
        io.execute {
            val start = if (todayOnly) {
                java.time.LocalDate.now()
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } else null
            maintainStorageNow()
            val rows = db.search(query, 300, start)
            val totalMatches = db.count(query, start)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(rows)
                status.text = rows.size.toString() + " shown • " +
                    totalMatches + " matching • " +
                    if (serviceEnabled()) "service on" else "service off" +
                    " • " + if (Prefs.enabled(this)) "capture on" else "capture paused" +
                    " • last: " + lastCaptureText() +
                    if (Prefs.lastError(this).isBlank()) "" else " • error: " + Prefs.lastError(this)
                updateScrubber()
            }
        }
    }

    private fun updateScrubber() {
        if (!::scrubber.isInitialized || adapter.itemCount == 0) return
        val lm = timeline.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        scrubber.setProgress(first.toFloat() / (adapter.itemCount - 1).coerceAtLeast(1))
    }

    private fun showSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 18.dp(), 24.dp(), 24.dp())
        }
        panel.addView(TextView(this).apply {
            text = "Capture & privacy"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
        })
        panel.addView(TextView(this).apply {
            text = "Long-press a timeline item to delete it. Default storage is private to EmRecall. On Android 13+, a sideloaded accessibility service may also require Allow restricted settings in Android Settings."
            textSize = 14f
            alpha = 0.72f
            setPadding(0, 8.dp(), 0, 18.dp())
        })
        panel.addView(SwitchMaterial(this).apply {
            text = "Capture screenshots"
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setEnabled(this@MainActivity, checked)
                renderTimeline()
            }
        })
        panel.addView(SwitchMaterial(this).apply {
            text = "Lock EmRecall with biometrics"
            isChecked = Prefs.appLock(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAppLock(this@MainActivity, checked)
            }
        })
        panel.addView(MaterialButton(this).apply {
            text = "Run OCR on pending screenshots"
            setOnClickListener {
                getOcrIndexer().indexPending(
                    onProgress = { done, total ->
                        status.text = if (total == 0) "OCR: nothing pending" else "OCR: " + done + " / " + total
                    },
                    onDone = { renderTimeline() }
                )
                Toast.makeText(this@MainActivity, "OCR started", Toast.LENGTH_SHORT).show()
            }
        })
        panel.addView(MaterialButton(this).apply {
            text = "Retention: " + Prefs.retentionDays(this@MainActivity) + " days"
            setOnClickListener {
                val next = when (Prefs.retentionDays(this@MainActivity)) {
                    7 -> 30
                    30 -> 90
                    else -> 7
                }
                Prefs.setRetentionDays(this@MainActivity, next)
                text = "Retention: " + next + " days"
            }
        })
        panel.addView(MaterialButton(this).apply {
            text = "Capture interval: " + Prefs.interval(this@MainActivity) + " seconds"
            setOnClickListener {
                val next = when (Prefs.interval(this@MainActivity)) {
                    5L -> 7L
                    7L -> 10L
                    10L -> 15L
                    15L -> 20L
                    else -> 5L
                }
                Prefs.setInterval(this@MainActivity, next)
                text = "Capture interval: " + next + " seconds"
            }
        })
        panel.addView(MaterialButton(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        val excluded = EditText(this).apply {
            hint = "Additional excluded package names"
            setSingleLine(false)
            setText(Prefs.excluded(this@MainActivity).joinToString(", "))
        }
        panel.addView(excluded)
        panel.addView(MaterialButton(this).apply {
            text = "Save exclusions"
            setOnClickListener {
                Prefs.setExcluded(
                    this@MainActivity,
                    excluded.text.toString()
                        .split(',', '\n', ' ', '\t')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                )
                Toast.makeText(this@MainActivity, "Exclusions saved", Toast.LENGTH_SHORT).show()
            }
        })
        panel.addView(MaterialButton(this).apply {
            text = "Choose screenshot folder"
            setOnClickListener { chooseFolder() }
        })
        panel.addView(MaterialButton(this).apply {
            text = "Clear all stored memories"
            setOnClickListener {
                io.execute {
                    val paths = db.clear()
                    paths.forEach { StorageHelper.delete(this@MainActivity, it) }
                    runOnUiThread {
                        adapter.submit(emptyList())
                        Toast.makeText(this@MainActivity, "All memories deleted", Toast.LENGTH_SHORT).show()
                        renderTimeline()
                    }
                }
            }
        })
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(panel)
        dialog.show()
    }

    private fun deleteOne(row: MemoryDb.Row, notifyUser: Boolean = true) {
        io.execute {
            val path = db.delete(row.id)
            if (path != null) StorageHelper.delete(this@MainActivity, path)
            runOnUiThread {
                if (notifyUser) Toast.makeText(this, "Memory deleted", Toast.LENGTH_SHORT).show()
                renderTimeline()
            }
        }
    }

    private fun chooseFolder() {
        folderPickerLauncher.launch(null)
    }

    private fun getOcrIndexer(): OcrIndexer =
        ocrIndexer ?: OcrIndexer(this, db).also { ocrIndexer = it }

    private fun maintainStorage() {
        io.execute { maintainStorageNow() }
    }

    private fun maintainStorageNow() {
        db.labelsNeedingBackfill().forEach { row ->
            db.updateAppLabel(row.id, resolveAppLabel(row.packageName))
        }
        db.allReferences().forEach { ref ->
            if (!StorageHelper.exists(this, ref.path)) db.delete(ref.id)
        }
        val days = Prefs.retentionDays(this)
        if (days > 0) {
            val cutoff = System.currentTimeMillis() - days * 86_400_000L
            db.referencesOlderThan(cutoff).forEach { ref ->
                if (!StorageHelper.exists(this, ref.path) || StorageHelper.delete(this, ref.path)) {
                    db.delete(ref.id)
                }
            }
        }
    }

    private fun resolveAppLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrElse {
        packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
    }

    private fun serviceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.isEnabled &&
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == ScreenCaptureAccessibilityService::class.java.name
            }
    }

    private fun lastCaptureText(): String {
        val value = Prefs.lastCaptureAt(this)
        return if (value == 0L) "never"
        else java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(value))
    }

    private fun chip(text: String, checked: Boolean, action: () -> Unit) =
        Chip(this).apply {
            this.text = text
            isCheckable = true
            isChecked = checked
            setOnClickListener { action() }
        }

    private fun resolveColor(attr: Int): Int {
        val typed = theme.obtainStyledAttributes(intArrayOf(attr))
        return typed.getColor(0, 0).also { typed.recycle() }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
