package org.chaosorderx.dashspeed.ui.dashboard

import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.chaosorderx.dashspeed.camera.CameraManager
import org.chaosorderx.dashspeed.overlay.SpeedOverlayCanvas
import org.chaosorderx.dashspeed.ui.calibration.CalibrationActivity
import org.chaosorderx.dashspeed.ui.settings.SettingsBottomSheet

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    cameraManager: CameraManager,
    viewModel: MainViewModel,
) {
    val textMeasurer = rememberTextMeasurer()
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findActivity() ?: return
    val prefs by viewModel.prefs.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var lastAlertMessage by remember { mutableStateOf("") }
    uiState.alertMessage?.also { lastAlertMessage = it }

    // Apply screen brightness whenever the preference changes.
    LaunchedEffect(prefs.screenBrightness) {
        val lp = activity.window.attributes
        lp.screenBrightness = prefs.screenBrightness
        activity.window.attributes = lp
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { showSettings = true })
                },
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { cameraManager.bindPreview(it, lifecycleOwner) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        SpeedOverlayCanvas(
            annotations = uiState.annotations,
            tracks = uiState.tracks,
            textMeasurer = textMeasurer,
            modifier = Modifier.fillMaxSize(),
        )

        HudStrip(
            egoSpeedMph = uiState.egoSpeedMph,
            trackCount = uiState.trackCount,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = uiState.alertMessage != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
        ) {
            AlertBanner(message = lastAlertMessage)
        }

        // Left-edge strip: vertical drag adjusts screen brightness.
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .width(40.dp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            // Drag up (negative) → brighter; drag down → dimmer.
                            val delta = -dragAmount / size.height
                            val next = (prefs.screenBrightness + delta).coerceIn(0.05f, 1.0f)
                            viewModel.updateBrightness(next)
                        }
                    },
        )
    }

    if (showSettings) {
        SettingsBottomSheet(
            prefs = prefs,
            onThresholdChange = viewModel::updateAlertThreshold,
            onCooldownChange = viewModel::updateAlertCooldown,
            onBrightnessChange = viewModel::updateBrightness,
            onCalibrate = {
                showSettings = false
                activity.startActivity(Intent(activity, CalibrationActivity::class.java))
            },
            onDismiss = { showSettings = false },
        )
    }
}
