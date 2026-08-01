package org.chaosorderx.dashspeed.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.chaosorderx.dashspeed.ui.theme.DashSpeedColors
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTypography

@Composable
fun AlertBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "⚠  $message",
        style = DashSpeedTypography.AlertText,
        modifier =
            modifier
                .background(DashSpeedColors.AlertBackground)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
