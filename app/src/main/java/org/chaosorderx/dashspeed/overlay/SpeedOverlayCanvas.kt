package org.chaosorderx.dashspeed.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import org.chaosorderx.dashspeed.detection.ModelConfig
import org.chaosorderx.dashspeed.tracking.TrackedVehicle
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTypography
import kotlin.math.roundToInt

@Composable
fun SpeedOverlayCanvas(
    annotations: List<SpeedAnnotation>,
    tracks: List<TrackedVehicle>,
    textMeasurer: TextMeasurer,
    modifier: Modifier = Modifier,
) {
    val description =
        if (annotations.isEmpty()) {
            "Speed overlay"
        } else {
            annotations.joinToString(", ") { "~${it.speedMph.roundToInt()} mph" }
        }
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        // Letterbox-aware scale from model space (416×416) to canvas pixels
        val inputSize = ModelConfig.INPUT_SIZE.toFloat()
        val modelScale = minOf(size.width / inputSize, size.height / inputSize)
        val offX = (size.width - inputSize * modelScale) / 2f
        val offY = (size.height - inputSize * modelScale) / 2f

        fun modelToScreenX(x: Float) = x * modelScale + offX

        fun modelToScreenY(y: Float) = y * modelScale + offY

        // Ghost boxes for confirmed tracks that have no speed annotation yet
        val annotatedIds = annotations.map { it.trackId }.toSet()
        for (track in tracks) {
            if (track.id in annotatedIds) continue
            val b = track.boundingBox
            val screenLeft = modelToScreenX(b.left)
            val screenTop = modelToScreenY(b.top)
            val screenW = b.width() * modelScale
            val screenH = b.height() * modelScale

            drawRoundRect(
                color = Color.White.copy(alpha = 0.5f),
                topLeft = Offset(screenLeft, screenTop),
                size = Size(screenW, screenH),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )

            val idLabel = "#${track.id}"
            val measured = textMeasurer.measure(idLabel, DashSpeedTypography.HudCaption)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(screenLeft + 4.dp.toPx(), screenTop + 4.dp.toPx()),
            )
        }

        // Speed annotation boxes — modelBox mapped to screen space same as ghost boxes
        annotations.forEach { annotation ->
            val badgeColor = annotation.badgeColor()
            val label = "~${annotation.speedMph.roundToInt()} mph"
            val measured = textMeasurer.measure(label, DashSpeedTypography.SpeedLabel)
            val b = annotation.modelBox

            val screenLeft = modelToScreenX(b.left)
            val screenTop = modelToScreenY(b.top)
            val screenW = b.width() * modelScale
            val screenH = b.height() * modelScale

            drawRoundRect(
                color = badgeColor,
                topLeft = Offset(screenLeft, screenTop),
                size = Size(screenW, screenH),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )

            val badgeTop = (screenTop - measured.size.height - 12.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(
                color = badgeColor,
                topLeft = Offset(screenLeft, badgeTop),
                size = Size(measured.size.width + 16.dp.toPx(), measured.size.height + 8.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )

            drawText(
                textLayoutResult = measured,
                topLeft = Offset(screenLeft + 8.dp.toPx(), badgeTop + 4.dp.toPx()),
            )
        }
    }
}
