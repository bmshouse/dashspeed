package org.chaosorderx.dashspeed.tracking

import android.graphics.PointF
import android.graphics.RectF

data class PositionSample(
    val bottomCenter: PointF,
    val timestampMs: Long,
)

data class TrackedVehicle(
    val id: Int,
    val boundingBox: RectF,
    val history: List<PositionSample>,
    /** True when this frame had no matching detection and [boundingBox] is the predicted position. */
    val coasting: Boolean = false,
)
