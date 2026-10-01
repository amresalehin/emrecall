package com.emreh.snapmemory

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var db: MemoryDb
    private lateinit var timeline: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var folderStatus: TextView
    private var allMode = true
    private val folderPicker = 4107

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = MemoryDb(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::timeline.isInitialized) renderTimeline()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 12, 12)
        }
        toolbar.addView(TextView(this).apply {
            text = "EmRecall"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(MaterialButton(this).apply {
            text = "Settings"
            setOnClickListener { showSettings() }
        })
        root.addView(toolbar)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 0, 16, 8)
        }
        search = EditText(this).apply {
            hint = "Search your memories"
            setSingleLine(true)
            setPadding(18, 0, 18, 0)
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, 54.dp(), 1f))
        searchRow.addView(MaterialButton(this).apply {
            text = "Search"
            setOnClickListener { renderTimeline() }
        })
        root.addView(searchRow)

        val filters = ChipGroup(this).apply {
            isSingleSelection = true
            setPadding(16, 0, 16, 8)
        }
        filters.addView(chip("All memories", true) { allMode = true; renderTimeline() })
        filters.addView(chip("Today", false) { allMode = false; renderTimeline(todayOnly = true) })
        root.addView(filters)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 32)
                timeline = this
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderTimeline()
    }

    private fun renderTimeline(todayOnly: Boolean = !allMode) {
        if (!::timeline.isInitialized) return
        timeline.removeAllViews()
        val query = search.text?.toString()?.trim().orEmpty()
        var rows = db.search(query)
        if (todayOnly) {
            val start = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            rows = rows.filter { it.capturedAt >= start }
        }

        val hero = MaterialCardView(this).apply {
            radius = 28f
            cardElevation = 0f
            setStrokeWidth(1)
            setContentPadding(20, 18, 20, 18)
        }
        val heroBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroBody.addView(TextView(this@MainActivity).apply {
            text = if (rows.isEmpty()) "Your memory timeline" else rows.size.toString() + " moments in your timeline"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        status = TextView(this).apply {
            text = "Screenshots are kept until you delete them."
            textSize = 13f
            alpha = 0.72f
            setPadding(0, 6, 0, 0)
        }
        heroBody.addView(status)
        hero.addView(heroBody)
        timeline.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18.dp() })

        if (rows.isEmpty()) {
            timeline.addView(TextView(this).apply {
                text = "Nothing here yet. Enable EmRecall in Accessibility settings, then use your phone normally."
                textSize = 16f
                alpha = 0.72f
                setPadding(8, 32, 8, 32)
            })
            return
        }

        val dayFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        var lastDay = ""
        rows.forEach { row ->
            val day = dayFormat.format(Date(row.capturedAt))
            if (day != lastDay) {
                timeline.addView(TextView(this).apply {
                    text = if (isToday(row.capturedAt)) "Today" else day
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(8, 12, 8, 12)
                })
                lastDay = day
            }
            addTimelineRow(row, timeFormat.format(Date(row.capturedAt)))
        }
    }

    private fun addTimelineRow(row: MemoryDb.Row, time: String) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, 10, 10, 0)
        }
        rail.addView(TextView(this@MainActivity).apply {
            text = time
            textSize = 12f
            alpha = 0.7f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(56.dp(), -2))
        rail.addView(TextView(this@MainActivity).apply {
            text = "●"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 0)
        }, LinearLayout.LayoutParams(56.dp(), -2))
        line.addView(rail)

        val card = MaterialCardView(this).apply {
            radius = 22f
            cardElevation = 1f
            setStrokeWidth(1)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PreviewActivity::class.java).putExtra("path", row.path))
            }
        }
        val body = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(StorageHelper.open(this@MainActivity, row.path))
            minimumHeight = 150.dp()
            contentDescription = "Screenshot captured at " + time
        }, LinearLayout.LayoutParams(-1, 165.dp()))
        body.addView(TextView(this@MainActivity).apply {
            text = appLabel(row.packageName)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(14, 12, 14, 2)
        })
        body.addView(TextView(this@MainActivity).apply {
            text = if (row.ocrText.isNotBlank()) row.ocrText.replace("\n", " ").take(100) else "Tap to inspect this moment"
            textSize = 12f
            alpha = 0.7f
            setPadding(14, 3, 14, 12)
        })
        card.addView(body)
        line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = 12.dp() })
        timeline.addView(line)
    }

    private fun showSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 24)
        }
        panel.addView(TextView(this).apply {
            text = "Capture & storage"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
        })
        panel.addView(TextView(this).apply {
            text = "Your screenshots are kept permanently. EmRecall never auto-deletes them."
            textSize = 14f
            alpha = 0.72f
            setPadding(0, 8, 0, 18)
        })
        panel.addView(MaterialButton(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        folderStatus = TextView(this).apply {
            text = folderText()
            textSize = 13f
            alpha = 0.72f
            setPadding(0, 4, 0, 8)
        }
        panel.addView(folderStatus)
        panel.addView(MaterialButton(this).apply {
            text = "Choose screenshot folder"
            setOnClickListener { chooseFolder() }
        })
        val excluded = EditText(this).apply {
            hint = "Excluded package names"
            setSingleLine(false)
            setText(Prefs.excluded(this@MainActivity).joinToString(", "))
        }
        panel.addView(excluded)
        panel.addView(MaterialButton(this).apply {
            text = "Save exclusions"
            setOnClickListener {
                Prefs.setExcluded(this@MainActivity, excluded.text.toString().split(',', '\n', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                Toast.makeText(this@MainActivity, "Exclusions saved", Toast.LENGTH_SHORT).show()
            }
        })
        panel.addView(MaterialButton(this).apply {
            text = "Capture interval: " + Prefs.interval(this@MainActivity) + " seconds"
            setOnClickListener {
                val next = when (Prefs.interval(this@MainActivity)) { 5L -> 7L; 7L -> 10L; 10L -> 15L; 15L -> 20L; else -> 5L }
                Prefs.setInterval(this@MainActivity, next)
                text = "Capture interval: " + next + " seconds"
            }
        })
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(panel)
        dialog.show()
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, folderPicker)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == folderPicker && resultCode == RESULT_OK) {
            data?.data?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Prefs.setFolderUri(this, it.toString())
                if (::folderStatus.isInitialized) folderStatus.text = folderText()
                Toast.makeText(this, "Screenshot folder selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun folderText(): String = if (Prefs.folderUri(this) != null) "Saving to your selected folder, organized by date." else "No folder selected. Screenshots use EmRecall's private storage."

    private fun chip(text: String, checked: Boolean, action: () -> Unit) = Chip(this).apply {
        this.text = text
        isCheckable = true
        isChecked = checked
        setOnClickListener { action() }
    }

    private fun appLabel(packageName: String): String = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private fun isToday(timestamp: Long): Boolean {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        return fmt.format(Date(timestamp)) == fmt.format(Date())
    }

    private fun resolveColor(attr: Int): Int {
        val typed = theme.obtainStyledAttributes(intArrayOf(attr))
        return typed.getColor(0, 0).also { typed.recycle() }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
