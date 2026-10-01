package com.emreh.snapmemory

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Persistent timeline scrubber. Dragging the thumb or tapping the track
 * moves the history ScrollView rather than relying on Android's transient
 * scrollbar.
 */
class TimelineScrubberView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f
    private var thumbHeight = 64f
    private var dragging = false
    private var onPositionChanged: ((Float) -> Unit)? = null

    init {
        val density = resources.displayMetrics.density
        thumbHeight = 64f * density
        isClickable = true
        contentDescription = "Timeline history scrubber"
        trackPaint.strokeCap = Paint.Cap.ROUND
        tickPaint.strokeCap = Paint.Cap.ROUND
    }

    fun setOnPositionChanged(listener: (Float) -> Unit) {
        onPositionChanged = listener
    }

    fun setProgress(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val centerX = width / 2f
        val top = 10f * d
        val bottom = height - 10f * d
        val trackHeight = max(1f, bottom - top)

        trackPaint.color = resolveColor(com.google.android.material.R.attr.colorOutlineVariant)
        trackPaint.strokeWidth = 4f * d
        canvas.drawLine(centerX, top, centerX, bottom, trackPaint)

        // Small time markers make the bar read as a history axis, not a normal scrollbar.
        tickPaint.color = resolveColor(com.google.android.material.R.attr.colorOutline)
        tickPaint.strokeWidth = 2f * d
        for (i in 0..6) {
            val y = top + trackHeight * (i / 6f)
            canvas.drawLine(centerX - 7f * d, y, centerX + 7f * d, y, tickPaint)
        }

        val available = max(1f, trackHeight - thumbHeight)
        val thumbTop = top + available * fraction
        val thumbBottom = thumbTop + thumbHeight
        thumbPaint.color = resolveColor(android.R.attr.colorAccent)
        val rect = RectF(
            centerX - 9f * d,
            thumbTop,
            centerX + 9f * d,
            min(thumbBottom, bottom)
        )
        canvas.drawRoundRect(rect, 10f * d, 10f * d, thumbPaint)

        trackPaint.color = resolveColor(com.google.android.material.R.attr.colorOnPrimary)
        trackPaint.strokeWidth = 2f * d
        canvas.drawLine(centerX - 4f * d, thumbTop + thumbHeight / 2f, centerX + 4f * d, thumbTop + thumbHeight / 2f, trackPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        val top = 10f * d
        val bottom = height - 10f * d
        val available = max(1f, bottom - top - thumbHeight)
        val thumbTop = top + available * fraction
        val y = event.y

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = y in (thumbTop - 18f * d)..(thumbTop + thumbHeight + 18f * d)
                if (!dragging) {
                    updateFromTouch(y, top, available)
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) updateFromTouch(y, top, available)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                true
            }
            else -> true
        }
    }

    private fun updateFromTouch(y: Float, top: Float, available: Float) {
        fraction = ((y - top - thumbHeight / 2f) / available).coerceIn(0f, 1f)
        onPositionChanged?.invoke(fraction)
        invalidate()
    }

    private fun resolveColor(attr: Int): Int {
        val typed = context.theme.obtainStyledAttributes(intArrayOf(attr))
        return typed.getColor(0, 0).also { typed.recycle() }
    }
}
