package com.emreh.snapmemory

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var db: MemoryDb
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var resultBox: LinearLayout
    private var ocrIndexer: OcrIndexer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = MemoryDb(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        setContentView(ScrollView(this).apply {
            addView(content)
        })

        content.addView(TextView(this).apply {
            text = "SnapMemory Lite"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 16)
        }
        content.addView(status)

        content.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        val enabled = CheckBox(this).apply {
            text = "Capture enabled"
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setEnabled(this@MainActivity, checked)
            }
        }
        content.addView(enabled)

        val interval = Spinner(this)
        interval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("5 seconds", "7 seconds", "10 seconds", "15 seconds", "20 seconds")
        )
        val intervalChoices = longArrayOf(5, 7, 10, 15, 20)
        interval.setSelection(
            intervalChoices.indexOf(Prefs.interval(this)).coerceAtLeast(0)
        )
        interval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                Prefs.setInterval(this@MainActivity, intervalChoices[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        content.addView(label("Capture interval"))
        content.addView(interval)

        val retention = Spinner(this)
        retention.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("7 days", "30 days", "90 days")
        )
        val retentionChoices = intArrayOf(7, 30, 90)
        retention.setSelection(
            retentionChoices.indexOf(Prefs.retention(this)).coerceAtLeast(0)
        )
        retention.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                Prefs.setRetention(this@MainActivity, retentionChoices[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        content.addView(label("Retention"))
        content.addView(retention)

        val excluded = EditText(this).apply {
            hint = "com.bank.app, com.password.manager"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(false)
            setText(Prefs.excluded(this@MainActivity).joinToString(","))
        }
        content.addView(label("Excluded packages"))
        content.addView(excluded)

        content.addView(Button(this).apply {
            text = "Save exclusions"
            setOnClickListener {
                val set = excluded.text.toString()
                    .split(',', '\n', ' ', '\t')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                Prefs.setExcluded(this@MainActivity, set)
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })

        val ocrButton = Button(this).apply {
            text = "Index missing text (on-demand)"
            setOnClickListener {
                if (ocrIndexer != null) return@setOnClickListener

                val indexer = OcrIndexer(db)
                ocrIndexer = indexer
                isEnabled = false

                indexer.indexPending(
                    onProgress = { done, total ->
                        runOnUiThread {
                            text = if (total == 0) {
                                "No OCR work pending"
                            } else {
                                "Indexing text $done/$total"
                            }
                        }
                    },
                    onDone = {
                        runOnUiThread {
                            isEnabled = true
                            text = "Index missing text (on-demand)"
                            ocrIndexer?.close()
                            ocrIndexer = null
                            refreshStatus()
                            runSearch()
                        }
                    }
                )
            }
        }
        content.addView(ocrButton)

        content.addView(label("Search local history"))

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        search = EditText(this).apply {
            hint = "package name or OCR text"
            setSingleLine(true)
        }
        searchRow.addView(
            search,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        searchRow.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { runSearch() }
        })
        content.addView(searchRow)

        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(resultBox)

        refreshStatus()
        runSearch()
    }

    override fun onDestroy() {
        ocrIndexer?.close()
        ocrIndexer = null
        db.close()
        super.onDestroy()
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        setPadding(0, 16, 0, 4)
        textSize = 13f
    }

    private fun refreshStatus() {
        status.text =
            "Stored snapshots: ${db.count()}\nService must be enabled manually in Android Settings."
    }

    private fun runSearch() {
        resultBox.removeAllViews()

        val rows = db.search(search.text.toString())
        if (rows.isEmpty()) {
            resultBox.addView(TextView(this).apply {
                text = "No captures yet."
                setPadding(0, 20, 0, 0)
            })
            return
        }

        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        rows.forEach { row ->
            val item = TextView(this).apply {
                text = buildString {
                    append(df.format(Date(row.capturedAt)))
                    append("\n")
                    append(row.packageName)
                    if (row.ocrText.isNotBlank()) {
                        append("\n")
                        append(row.ocrText.take(140))
                    }
                }
                setPadding(0, 16, 0, 16)
                textSize = 14f
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, PreviewActivity::class.java)
                            .putExtra("path", row.path)
                    )
                }
            }
            resultBox.addView(item)
        }
    }
}
