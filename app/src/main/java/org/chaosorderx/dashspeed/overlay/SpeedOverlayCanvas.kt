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
import org.chaosorderx.dashspeed.detection.FrameGeometry
import org.chaosorderx.dashspeed.detection.ModelConfig
import org.chaosorderx.dashspeed.tracking.LeadVehicle
import org.chaosorderx.dashspeed.tracking.TrackedVehicle
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTypography
import kotlin.math.roundToInt

private val LeadColor = Color(0xFF00E5FF)

/** "LEAD ≈49 m · ≈62 mph", with parts omitted when unknown. */
internal fun leadLabel(lead: LeadVehicle): String {
    val parts = mutableListOf("LEAD ≈${lead.distanceMeters.roundToInt()} m")
    lead.speedMph?.let { parts += (if (lead.speedIsApproximate) "≈" else "~") + "${it.roundToInt()} mph" }
    return parts.joinToString(" · ")
}

@Composable
fun SpeedOverlayCanvas(
    annotations: List<SpeedAnnotation>,
    tracks: List<TrackedVehicle>,
    lead: LeadVehicle? = null,
    showSpeedTags: Boolean = false,
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
        // Boxes are in frame-normalised space (full frame = 0..FRAME_SPACE); map them the same
        // way PreviewView's FILL_CENTER draws the preview, so boxes land on the vehicles.
        val fill = FrameGeometry.fillCenter(size.width, size.height)

        fun modelToScreenX(x: Float) = fill.toViewX(x)

        fun modelToScreenY(y: Float) = fill.toViewY(y)

        val scaleX = fill.dispW / ModelConfig.FRAME_SPACE
        val scaleY = fill.dispH / ModelConfig.FRAME_SPACE

        // Ghost boxes for confirmed tracks that have no speed annotation yet
        val annotatedIds = annotations.map { it.trackId }.toSet()
        for (track in tracks) {
            if (track.id in annotatedIds || track.id == lead?.trackId) continue
            val b = track.boundingBox
            val screenLeft = modelToScreenX(b.left)
            val screenTop = modelToScreenY(b.top)
            val screenW = b.width() * scaleX
            val screenH = b.height() * scaleY

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
        // The lead car is drawn below with its own (single) tag, so it is skipped here.
        annotations.filter { it.trackId != lead?.trackId }.forEach { annotation ->
            val badgeColor = annotation.badgeColor()
            val label = (if (annotation.approximate) "≈" else "~") + "${annotation.speedMph.roundToInt()} mph"
            val measured = textMeasurer.measure(label, DashSpeedTypography.SpeedLabel)
            val b = annotation.modelBox

            val screenLeft = modelToScreenX(b.left)
            val screenTop = modelToScreenY(b.top)
            val screenW = b.width() * scaleX
            val screenH = b.height() * scaleY

            drawRoundRect(
                color = badgeColor,
                topLeft = Offset(screenLeft, screenTop),
                size = Size(screenW, screenH),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )

            if (!showSpeedTags) return@forEach

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

        // The lead vehicle is always outlined, even when it is too small for a speed estimate.
        lead?.let { vehicle ->
            val b = vehicle.box
            val left = modelToScreenX(b.left)
            val top = modelToScreenY(b.top)
            val w = b.width() * scaleX
            val h = b.height() * scaleY
            drawRoundRect(
                color = LeadColor,
                topLeft = Offset(left - 3.dp.toPx(), top - 3.dp.toPx()),
                size = Size(w + 6.dp.toPx(), h + 6.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = 3.dp.toPx()),
            )
            val measured = textMeasurer.measure(leadLabel(vehicle), DashSpeedTypography.SpeedLabel)
            // Always above the box, in the same spot a speed tag would take.
            val labelTop = (top - measured.size.height - 12.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(
                color = LeadColor,
                topLeft = Offset(left, labelTop),
                size = Size(measured.size.width + 16.dp.toPx(), measured.size.height + 8.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(left + 8.dp.toPx(), labelTop + 4.dp.toPx()),
                color = Color.Black,
            )
        }
    }
}
