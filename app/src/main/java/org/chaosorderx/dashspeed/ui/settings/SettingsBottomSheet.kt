package org.chaosorderx.dashspeed.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.chaosorderx.dashspeed.data.UserPreferences
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTypography
import kotlin.math.roundToInt

/** Callbacks for the overlay controls: hood / phone-mount exclusion area and speed tags. */
data class OverlayActions(
    val onRelearn: () -> Unit,
    val onShowOverlayChange: (Boolean) -> Unit,
    val onShowSpeedTagsChange: (Boolean) -> Unit,
    val onPaint: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    prefs: UserPreferences,
    onThresholdChange: (Float) -> Unit,
    onCooldownChange: (Int) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onCalibrate: () -> Unit,
    overlay: OverlayActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        SettingsContent(
            prefs = prefs,
            onThresholdChange = onThresholdChange,
            onCooldownChange = onCooldownChange,
            onBrightnessChange = onBrightnessChange,
            onCalibrate = onCalibrate,
            overlay = overlay,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun SettingsContent(
    prefs: UserPreferences,
    onThresholdChange: (Float) -> Unit,
    onCooldownChange: (Int) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onCalibrate: () -> Unit,
    overlay: OverlayActions,
    onDismiss: () -> Unit,
) {
    var threshold by remember(prefs.alertThresholdMph) { mutableFloatStateOf(prefs.alertThresholdMph) }
    var cooldown by remember(prefs.alertCooldownSec) { mutableIntStateOf(prefs.alertCooldownSec) }
    var brightness by remember(prefs.screenBrightness) { mutableFloatStateOf(prefs.screenBrightness) }

    // Scrollable: in landscape the sheet is short, and a fixed-height Column squeezed the later rows to nothing.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = DashSpeedTypography.HudPrimary)

        Spacer(Modifier.height(4.dp))

        // Alert threshold
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Alert threshold", style = DashSpeedTypography.HudCaption)
            Text("${threshold.roundToInt()} mph", style = DashSpeedTypography.HudCaption)
        }
        Slider(
            value = threshold,
            onValueChange = { threshold = it },
            onValueChangeFinished = { onThresholdChange(threshold) },
            valueRange = 5f..30f,
            modifier = Modifier.fillMaxWidth(),
        )

        // Alert cooldown
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Alert cooldown", style = DashSpeedTypography.HudCaption)
            Text("${cooldown}s", style = DashSpeedTypography.HudCaption)
        }
        Slider(
            value = cooldown.toFloat(),
            onValueChange = { cooldown = it.roundToInt() },
            onValueChangeFinished = { onCooldownChange(cooldown) },
            valueRange = 4f..30f,
            steps = 25,
            modifier = Modifier.fillMaxWidth(),
        )

        // Screen brightness
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Brightness", style = DashSpeedTypography.HudCaption)
            Text("${(brightness * 100).roundToInt()}%", style = DashSpeedTypography.HudCaption)
        }
        Slider(
            value = brightness,
            onValueChange = { brightness = it },
            onValueChangeFinished = { onBrightnessChange(brightness) },
            valueRange = 0.05f..1.0f,
            modifier = Modifier.fillMaxWidth(),
        )

        // Speed tags on every car; the lead car always keeps its own tag.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show speed tags on all cars", style = DashSpeedTypography.HudCaption)
            Switch(checked = prefs.showSpeedTags, onCheckedChange = overlay.onShowSpeedTagsChange)
        }

        // Calibration shortcut
        Button(
            onClick = onCalibrate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open Calibration")
        }

        // Hood / phone-mount filter: learned while driving, or painted by hand.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show excluded area", style = DashSpeedTypography.HudCaption)
            Switch(checked = prefs.showStaticOverlay, onCheckedChange = overlay.onShowOverlayChange)
        }
        OutlinedButton(
            onClick = overlay.onPaint,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Paint excluded area")
        }
        OutlinedButton(
            onClick = overlay.onRelearn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Re-learn hood / mount area")
        }

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Close")
        }

        Spacer(Modifier.height(8.dp))
    }
}
