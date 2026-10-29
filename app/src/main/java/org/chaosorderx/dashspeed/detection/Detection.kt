package org.chaosorderx.dashspeed.detection

import android.graphics.RectF

data class Detection(
    val boundingBox: RectF,
    val classId: Int,
    val confidence: Float,
    /** True when the box touches the edge of the cropped band, so its bottom row is not the vehicle's. */
    val bottomClipped: Boolean = false,
)
