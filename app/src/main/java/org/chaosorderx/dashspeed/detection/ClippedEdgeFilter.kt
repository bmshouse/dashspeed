package org.chaosorderx.dashspeed.detection

import kotlin.math.abs

/**
 * With a band model the hood is cut off by the *strip's* bottom edge, not the frame's, so its box ends at the same
 * row for as long as the strip stays put. A wide box clipped at that row for [HoodDetector.PERSIST_MS] of driving
 * marks the row as a hood edge; later wide boxes clipped there are dropped. Analysis-thread only.
 */
internal class ClippedEdgeFilter {
    private class Candidate(
        val bottom: Float,
        val firstMs: Long,
        var lastMs: Long,
    )

    private val candidates = mutableListOf<Candidate>()
    private val edges = mutableListOf<Float>()

    fun reset() {
        candidates.clear()
        edges.clear()
    }

    /** Wide box cut off by the strip's bottom edge: the only kind this filter judges. */
    fun applies(d: Detection): Boolean =
        d.bottomClipped && d.boundingBox.right - d.boundingBox.left >= MIN_WIDTH_FRACTION * ModelConfig.FRAME_SPACE

    fun isHood(d: Detection): Boolean = edges.any { abs(it - d.boundingBox.bottom) <= TOLERANCE_PX }

    /** Counts this box towards flagging its edge row; call only while driving. */
    fun observe(
        d: Detection,
        timestampMs: Long,
    ) {
        candidates.removeAll { timestampMs - it.lastMs > HoodDetector.FORGET_MS }
        val bottom = d.boundingBox.bottom
        val candidate = candidates.firstOrNull { abs(it.bottom - bottom) <= TOLERANCE_PX }
        if (candidate == null) {
            candidates += Candidate(bottom, timestampMs, timestampMs)
            return
        }
        candidate.lastMs = timestampMs
        if (timestampMs - candidate.firstMs >= HoodDetector.PERSIST_MS) {
            candidates.remove(candidate)
            edges += candidate.bottom
        }
    }

    private companion object {
        const val TOLERANCE_PX = 6f
        const val MIN_WIDTH_FRACTION = 0.2f
    }
}
