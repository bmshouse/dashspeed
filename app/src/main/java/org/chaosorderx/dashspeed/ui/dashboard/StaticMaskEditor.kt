package org.chaosorderx.dashspeed.ui.dashboard

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.chaosorderx.dashspeed.detection.FrameGeometry
import org.chaosorderx.dashspeed.detection.StaticMask
import org.chaosorderx.dashspeed.overlay.StaticMaskOverlay
import kotlin.math.abs
import kotlin.math.max

/**
 * Full-screen editor drawn over the live preview: drag to paint cells as excluded (or erase).
 * Starts from [initial]; [onDone] receives the edited mask, [onCancel] discards edits.
 */
@Composable
fun StaticMaskEditor(
    initial: StaticMask,
    onDone: (StaticMask) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The array is replaced (never mutated) on each change so Compose sees a new state value.
    var cells by remember { mutableStateOf(initial.toCells()) }
    var erasing by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        StaticMaskOverlay(cells = cells, modifier = Modifier.fillMaxSize())

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(erasing) {
                        detectDragGestures(
                            onDragStart = { start -> cells = paintAt(cells, size, start, start, !erasing) },
                            onDrag = { change, _ ->
                                cells = paintAt(cells, size, change.previousPosition, change.position, !erasing)
                            },
                        )
                    },
        )

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = !erasing, onClick = { erasing = false }, label = { Text("Paint") })
            FilterChip(selected = erasing, onClick = { erasing = true }, label = { Text("Erase") })
            OutlinedButton(onClick = { cells = BooleanArray(cells.size) }) { Text("Clear") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onDone(StaticMask(cells)) }) { Text("Done") }
        }
    }
}

/** Returns a copy of [cells] with every cell along the drag from [from] to [to] set to [value]. */
private fun paintAt(
    cells: BooleanArray,
    viewSize: IntSize,
    from: Offset,
    to: Offset,
    value: Boolean,
): BooleanArray {
    val fill = FrameGeometry.fillCenter(viewSize.width.toFloat(), viewSize.height.toFloat())
    val cellPx = fill.dispW / StaticMask.COLS
    // Step in half-cell increments so fast drags don't skip cells.
    val steps = max(1, (max(abs(to.x - from.x), abs(to.y - from.y)) / (cellPx / 2f)).toInt())
    val result = cells.copyOf()
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val col = fill.colAt(from.x + (to.x - from.x) * t)
        val row = fill.rowAt(from.y + (to.y - from.y) * t)
        if (col >= 0 && row >= 0) result[row * StaticMask.COLS + col] = value
    }
    return result
}
