package org.chaosorderx.dashspeed.data

data class UserPreferences(
    val mountHeightCm: Float = 120f,
    val tiltAngleDeg: Float = 5f,
    val alertThresholdMph: Float = 15f,
    val alertCooldownSec: Int = 8,
    val screenBrightness: Float = 0.7f,
    val homographyMatrixJson: String = "",
)
