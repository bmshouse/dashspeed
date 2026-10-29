package org.chaosorderx.dashspeed.camera

import org.chaosorderx.dashspeed.detection.ModelConfig

/**
 * Thins a camera stream to at most [ModelConfig.MAX_ANALYSIS_FPS] by timestamp. A 30 fps camera
 * passes every second frame; [JITTER_MS] of slack stops timestamp jitter from skipping a frame
 * that is, in effect, on schedule.
 */
class FrameRateGate(
    private val minGapMs: Long = DEFAULT_MIN_GAP_MS,
) {
    private var lastAcceptedMs: Long? = null

    /** True if a frame stamped [timestampMs] (any monotonic clock, ms) should be processed. */
    fun accept(timestampMs: Long): Boolean {
        val last = lastAcceptedMs
        val due = last == null || timestampMs - last >= minGapMs || timestampMs < last
        if (due) lastAcceptedMs = timestampMs
        return due
    }

    companion object {
        const val JITTER_MS = 8L
        const val DEFAULT_MIN_GAP_MS = 1000L / ModelConfig.MAX_ANALYSIS_FPS - JITTER_MS
    }
}
