package org.chaosorderx.dashspeed.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.chaosorderx.dashspeed.ui.theme.DashSpeedColors
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTypography
import kotlin.math.roundToInt

@Composable
fun HudStrip(
    egoSpeedMph: Float,
    trackCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(DashSpeedColors.HudBackground)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$trackCount tracked",
            style = DashSpeedTypography.HudCaption,
        )
        Text(
            text = "GPS  ${egoSpeedMph.roundToInt()} mph",
            style = DashSpeedTypography.HudPrimary,
        )
    }
}
