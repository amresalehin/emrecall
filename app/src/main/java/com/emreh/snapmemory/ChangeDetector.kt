package com.emreh.snapmemory

import kotlin.math.abs

class ChangeDetector(
    private val cellThreshold: Int = 9,
    private val changedFractionThreshold: Double = 0.08,
    private val meanDeltaThreshold: Double = 5.0
) {
    private var lastSavedProbe: IntArray? = null

    fun shouldCapture(probe: IntArray, force: Boolean = false): Boolean {
        require(probe.isNotEmpty())
        if (force || lastSavedProbe == null) return true
        val previous = lastSavedProbe!!
        require(previous.size == probe.size)
        var changedCells = 0
        var totalDelta = 0L
        for (i in probe.indices) {
            val delta = abs(probe[i] - previous[i])
            totalDelta += delta
            if (delta >= cellThreshold) changedCells++
        }
        return changedCells.toDouble() / probe.size >= changedFractionThreshold ||
            totalDelta.toDouble() / probe.size >= meanDeltaThreshold
    }

    fun markSaved(probe: IntArray) {
        lastSavedProbe = probe.copyOf()
    }

    fun reset() {
        lastSavedProbe = null
    }
}
