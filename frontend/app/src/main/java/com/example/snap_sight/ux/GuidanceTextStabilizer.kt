package com.example.snap_sight.ux

import com.example.snap_sight.cv.ReadinessBlocker

/**
 * Keeps the last camera-confirmed composition message across short tracker-only gaps.
 *
 * PREDICTED/HELD observations must still block the shutter, but replacing a just-confirmed
 * READY or movement message on every propagation frame makes the UI flicker. STALE and LOST
 * remain visible immediately because they mean the last real observation is no longer usable.
 */
internal class GuidanceTextStabilizer {
    private var lastConfirmedText: String? = null

    fun reset() {
        lastConfirmedText = null
    }

    fun stabilize(
        proposedText: String,
        subjectDetected: Boolean,
        blockers: Set<ReadinessBlocker>,
    ): String {
        if (!subjectDetected) {
            reset()
            return proposedText
        }

        val observationUncertain = blockers.any {
            it == ReadinessBlocker.PREDICTED ||
                it == ReadinessBlocker.HELD ||
                it == ReadinessBlocker.STALE
        }
        if (!observationUncertain) {
            lastConfirmedText = proposedText
            return proposedText
        }

        // A stale observation is a real UI state transition, not an ordinary keyframe gap.
        if (ReadinessBlocker.STALE in blockers) return proposedText
        return lastConfirmedText ?: proposedText
    }
}
