package org.chaosorderx.dashspeed.overlay

import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import org.chaosorderx.dashspeed.ui.theme.DashSpeedColors

data class SpeedAnnotation(
    val trackId: Int,
    val modelBox: RectF,
    val speedMph: Float,
    val relativeMph: Float,
) {
    // RectF.toString() is not available in JVM unit-test stubs; exclude modelBox from toString.
    override fun toString() = "SpeedAnnotation(trackId=$trackId, speedMph=$speedMph, relativeMph=$relativeMph)"

    fun badgeColor(): Color =
        when {
            relativeMph < -5f -> DashSpeedColors.BadgeRed // closing — we're approaching
            relativeMph > 5f -> DashSpeedColors.BadgeGreen // pulling away — safe
            else -> DashSpeedColors.BadgeAmber
        }
}
