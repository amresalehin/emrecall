package com.emreh.snapmemory

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TimelineAdapter(
    private val context: Context,
    private val onClick: (MemoryDb.Row) -> Unit,
    private val onLongClick: (MemoryDb.Row) -> Boolean,
    private val onMissing: (MemoryDb.Row) -> Unit
) : RecyclerView.Adapter<TimelineAdapter.VH>() {
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = android.util.LruCache<String, Bitmap>(16)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var rows: List<MemoryDb.Row> = emptyList()

    fun submit(newRows: List<MemoryDb.Row>) { rows = newRows; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = MaterialCardView(context).apply {
            radius = 22f
            cardElevation = 1f
            setStrokeWidth(1)
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 12.dp(context)) }
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(-1, 165.dp(context)) }
        val app = TextView(context).apply { textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(14.dp(context), 12.dp(context), 14.dp(context), 2.dp(context)) }
        val meta = TextView(context).apply { textSize = 12f; alpha = 0.7f; setPadding(14.dp(context), 3.dp(context), 14.dp(context), 12.dp(context)) }
        body.addView(image); body.addView(app); body.addView(meta); root.addView(body)
        return VH(root, image, app, meta)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.image.tag = row.path
        holder.image.setImageDrawable(null)
        val cached = cache.get(row.path)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
        } else {
            executor.execute {
                val bitmap = StorageHelper.open(context, row.path, 720, 720)
                if (bitmap != null) cache.put(row.path, bitmap) else main.post { onMissing(row) }
                main.post {
                    if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION && holder.image.tag == row.path) holder.image.setImageBitmap(bitmap)
                }
            }
        }
        holder.app.text = row.appLabel.ifBlank { appLabel(row.packageName) }
        holder.meta.text = timeFormat.format(Date(row.capturedAt)) + " • " +
            if (row.ocrText.isNotBlank()) row.ocrText.replace("\n", " ").take(100) else "Tap to inspect this moment"
        holder.itemView.setOnClickListener { onClick(row) }
        holder.itemView.setOnLongClickListener { onLongClick(row) }
    }

    override fun getItemCount() = rows.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        executor.shutdownNow(); cache.evictAll(); super.onDetachedFromRecyclerView(recyclerView)
    }

    class VH(root: MaterialCardView, val image: ImageView, val app: TextView, val meta: TextView) : RecyclerView.ViewHolder(root)

    private fun appLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrElse { packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() } }

    private fun Int.dp(context: Context) = (this * context.resources.displayMetrics.density).toInt()
}