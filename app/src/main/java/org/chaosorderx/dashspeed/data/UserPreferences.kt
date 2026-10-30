package org.chaosorderx.dashspeed.data

data class UserPreferences(
    val mountHeightCm: Float = 120f,
    val tiltAngleDeg: Float = 5f,
    val alertThresholdMph: Float = 15f,
    val alertCooldownSec: Int = 8,
    val screenBrightness: Float = 0.7f,
    val homographyMatrixJson: String = "",
    val staticMaskBits: String = "",
    val showStaticOverlay: Boolean = false,
    /** Speed tags above every tracked car; off by default so only the lead car's tag shows. */
    val showSpeedTags: Boolean = false,
    /** Learned centre of the band strip (fraction of frame height); 0 = not learned yet. */
    val bandCenterFrac: Float = 0f,
    /** Learned column (frame space) of the road's vanishing point, used to place cars in lanes; 0 = not learned. */
    val roadCenterX: Float = 0f,
)
