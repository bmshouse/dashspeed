package org.chaosorderx.dashspeed.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun DashSpeedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = dashSpeedColorScheme,
        typography = dashSpeedTypography,
        shapes = dashSpeedShapes,
        content = content,
    )
}
