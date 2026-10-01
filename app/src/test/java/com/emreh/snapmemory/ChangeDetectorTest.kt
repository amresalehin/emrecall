package com.emreh.snapmemory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeDetectorTest {
    @Test
    fun firstProbeCaptures() {
        val detector = ChangeDetector()
        assertTrue(detector.shouldCapture(IntArray(4) { 100 }))
    }

    @Test
    fun identicalProbeDoesNotCapture() {
        val detector = ChangeDetector()
        val probe = IntArray(4) { 100 }
        detector.markSaved(probe)
        assertFalse(detector.shouldCapture(probe))
    }

    @Test
    fun sparseLargeCellChangesAreDetected() {
        val detector = ChangeDetector(
            cellThreshold = 9,
            changedFractionThreshold = 0.25,
            meanDeltaThreshold = 1000.0
        )
        detector.markSaved(IntArray(4) { 100 })
        val changed = intArrayOf(100, 100, 100, 120)
        assertTrue(detector.shouldCapture(changed))
    }

    @Test
    fun slowDriftIsComparedToLastSavedProbe() {
        val detector = ChangeDetector(
            cellThreshold = 9,
            changedFractionThreshold = 1.0,
            meanDeltaThreshold = 5.0
        )
        val saved = IntArray(4) { 100 }
        detector.markSaved(saved)

        assertFalse(detector.shouldCapture(IntArray(4) { 102 }))
        assertFalse(detector.shouldCapture(IntArray(4) { 104 }))
        assertTrue(detector.shouldCapture(IntArray(4) { 106 }))
    }

    @Test
    fun forcedCaptureIgnoresVisualThreshold() {
        val detector = ChangeDetector()
        val probe = IntArray(4) { 100 }
        detector.markSaved(probe)
        assertTrue(detector.shouldCapture(probe, force = true))
    }
}
