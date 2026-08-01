package org.chaosorderx.dashspeed.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.chaosorderx.dashspeed.R

private val HudMono = FontFamily(Font(R.font.hud_mono, FontWeight.Bold))

object DashSpeedTypography {
    val SpeedLabel =
        TextStyle(
            fontFamily = HudMono,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color.White,
        )
    val HudPrimary =
        TextStyle(
            fontFamily = HudMono,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = androidx.compose.ui.graphics.Color.White,
        )
    val HudCaption =
        TextStyle(
            fontFamily = HudMono,
            fontSize = 12.sp,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
        )
    val AlertText =
        TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = DashSpeedColors.OnAlert,
        )
}

internal val dashSpeedTypography =
    Typography(
        bodyLarge = DashSpeedTypography.HudPrimary,
        bodyMedium = DashSpeedTypography.HudCaption,
        labelLarge = DashSpeedTypography.SpeedLabel,
    )
