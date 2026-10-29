package org.chaosorderx.dashspeed.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.chaosorderx.dashspeed.detection.FrameGeometry
import org.chaosorderx.dashspeed.detection.StaticMask

private val MaskTint = Color(0x66FF3B30)

/** Translucent tint over the cells excluded from detection (hood, phone mount). */
@Composable
fun StaticMaskOverlay(
    cells: BooleanArray,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val fill = FrameGeometry.fillCenter(size.width, size.height)
        val cellW = fill.dispW / StaticMask.COLS
        val cellH = fill.dispH / StaticMask.ROWS
        for (row in 0 until StaticMask.ROWS) {
            for (col in 0 until StaticMask.COLS) {
                if (!cells[row * StaticMask.COLS + col]) continue
                drawRect(
                    color = MaskTint,
                    topLeft = Offset(fill.offX + col * cellW, fill.offY + row * cellH),
                    size = Size(cellW + 1f, cellH + 1f),
                )
            }
        }
    }
}
