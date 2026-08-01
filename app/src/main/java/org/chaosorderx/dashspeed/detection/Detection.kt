package org.chaosorderx.dashspeed.detection

import android.graphics.RectF

data class Detection(
    val boundingBox: RectF,
    val classId: Int,
    val confidence: Float,
)
