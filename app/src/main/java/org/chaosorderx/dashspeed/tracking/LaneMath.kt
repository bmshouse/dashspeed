package org.chaosorderx.dashspeed.tracking

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure helpers for lane assignment and growth-rate maths (see [LaneAnalyzer]).
 *
 * Lateral position is measured in "car widths": a box's offset from the road's vanishing-point column,
 * scaled by the box's own width and the assumed 1.8 m car width. Perspective cancels, so it works for near
 * and far cars alike and needs no camera calibration.
 */
internal object LaneMath {
    const val CAR_WIDTH_M = 1.8f

    // Field logs: lateral offsets cluster at +0.5 m (own lane), −2.5 m, −5.5 m — i.e. ~3 m apart, and the
    // phone sits about half a metre off the lane centre.
    const val LANE_PITCH_M = 3.0f
    const val OWN_LANE_OFFSET_M = 0.5f
    const val HYSTERESIS_M = 0.6f

    fun lateralMeters(
        centerX: Float,
        width: Float,
        roadCenterX: Float,
    ): Float = (centerX - roadCenterX) * CAR_WIDTH_M / width

    /** Lane relative to ours (0 = own, −1 = left, +1 = right); [previous] is kept unless clearly left. */
    fun laneOf(
        lateralM: Float,
        previous: Int?,
    ): Int {
        val raw = ((lateralM - OWN_LANE_OFFSET_M) / LANE_PITCH_M).roundToInt()
        if (previous == null) return raw
        val previousCenter = OWN_LANE_OFFSET_M + previous * LANE_PITCH_M
        return if (abs(lateralM - previousCenter) <= LANE_PITCH_M / 2f + HYSTERESIS_M) previous else raw
    }

    fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /** Least-squares line `y = intercept + slope * x`; null when [x] has no spread. */
    fun fitLine(
        x: List<Double>,
        y: List<Double>,
    ): Pair<Double, Double>? {
        val n = x.size
        val meanX = x.sum() / n
        val meanY = y.sum() / n
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sxx += (x[i] - meanX) * (x[i] - meanX)
            sxy += (x[i] - meanX) * (y[i] - meanY)
        }
        if (sxx < MIN_SPREAD) return null
        val slope = sxy / sxx
        return slope to (meanY - slope * meanX)
    }

    private const val MIN_SPREAD = 1e-9
}
