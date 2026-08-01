package org.chaosorderx.dashspeed.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

object DashSpeedColors {
    val BadgeGreen = Color(0xFF4CAF50)
    val BadgeAmber = Color(0xFFFFC107)
    val BadgeRed = Color(0xFFF44336)
    val HudBackground = Color(0xCC000000)
    val AlertBackground = Color(0xFFBF360C)
    val OnAlert = Color(0xFFFFFFFF)
    val OverlayStroke = Color(0x80FFFFFF)
}

internal val dashSpeedColorScheme =
    darkColorScheme(
        primary = DashSpeedColors.BadgeAmber,
        secondary = DashSpeedColors.BadgeGreen,
        error = DashSpeedColors.BadgeRed,
        background = Color.Black,
        surface = DashSpeedColors.HudBackground,
        onBackground = Color.White,
        onSurface = Color.White,
    )
