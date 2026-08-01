package org.chaosorderx.dashspeed.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.chaosorderx.dashspeed.data.UserPreferences
import org.chaosorderx.dashspeed.ui.theme.DashSpeedColors
import kotlin.math.roundToInt

@Composable
fun CalibrationScreen(viewModel: CalibrationViewModel) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    CalibrationContent(
        prefs = prefs,
        onMountHeightChange = viewModel::setMountHeight,
        onTiltAngleChange = viewModel::setTiltAngle,
    )
}

@Composable
private fun CalibrationContent(
    prefs: UserPreferences,
    onMountHeightChange: (Float) -> Unit,
    onTiltAngleChange: (Float) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Camera Calibration", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Text("Mount Height: ${prefs.mountHeightCm.roundToInt()} cm")
        Slider(
            value = prefs.mountHeightCm,
            onValueChange = onMountHeightChange,
            valueRange = 60f..200f,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Text("Tilt Angle: ${prefs.tiltAngleDeg.roundToInt()}°")
        Slider(
            value = prefs.tiltAngleDeg,
            onValueChange = onTiltAngleChange,
            valueRange = 0f..20f,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        if (prefs.homographyMatrixJson.isNotEmpty()) {
            Text(
                "Calibration saved",
                color = DashSpeedColors.BadgeGreen,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
