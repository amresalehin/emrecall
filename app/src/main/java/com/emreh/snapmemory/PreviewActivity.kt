package com.emreh.snapmemory

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout

class PreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        val path = intent.getStringExtra("path") ?: return finish()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(8 + bars.left, 8 + bars.top, 8 + bars.right, 8 + bars.bottom)
                insets
            }
        }

        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(image, LinearLayout.LayoutParams(-1, -1))

        setContentView(root)
        root.requestApplyInsets()

        Thread {
            val bitmap = StorageHelper.open(this, path, 1440, 2560)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) image.setImageBitmap(bitmap)
            }
        }.start()
    }
}
