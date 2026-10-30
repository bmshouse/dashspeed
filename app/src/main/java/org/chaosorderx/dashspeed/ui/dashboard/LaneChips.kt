package org.chaosorderx.dashspeed.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.chaosorderx.dashspeed.tracking.LaneSummary
import org.chaosorderx.dashspeed.ui.theme.DashSpeedColors
import org.chaosorderx.dashspeed.ui.theme.DashSpeedTypography
import kotlin.math.roundToInt

// Readable at a glance from the driver's seat: 1.5x the HUD caption size.
private val chipStyle = DashSpeedTypography.HudCaption.copy(fontSize = 18.sp)

private const val MPS_TO_MPH = 2.237f
private const val TREND_DEADBAND_MPH = 3f
private const val SLOW_MPH = 10f

/** How one lane's traffic compares with us: speed, trend arrow and colour. Null speed means no usable cluster. */
internal data class LaneChipText(
    val label: String,
    val text: String,
    val color: Color,
)

internal fun laneChipText(
    lane: LaneSummary,
    egoMph: Float,
): LaneChipText {
    val label =
        when (lane.lane) {
            -1 -> "LEFT"
            1 -> "RIGHT"
            else -> "AHEAD"
        }
    val closing = lane.closingMps ?: return LaneChipText(label, "—", Color.White)
    // Closing > 0 means we are catching up, i.e. the traffic is slower than us.
    val relativeMph = -closing * MPS_TO_MPH
    val trafficMph = (egoMph + relativeMph).coerceAtLeast(0f)
    val arrow =
        when {
            relativeMph > TREND_DEADBAND_MPH -> "▲"
            relativeMph < -TREND_DEADBAND_MPH -> "▼"
            else -> "●"
        }
    val color =
        when {
            relativeMph < -SLOW_MPH -> DashSpeedColors.BadgeRed
            relativeMph < -TREND_DEADBAND_MPH -> DashSpeedColors.BadgeAmber
            else -> DashSpeedColors.BadgeGreen
        }
    return LaneChipText(label, "$arrow ${trafficMph.roundToInt()} · ${lane.cars}", color)
}

/** One small chip per lane (left, ahead, right) showing the traffic speed there and whether it is pulling away. */
@Composable
fun LaneChips(
    lanes: List<LaneSummary>,
    egoMph: Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lanes.sortedBy { it.lane }.forEach { lane ->
            val chip = laneChipText(lane, egoMph)
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DashSpeedColors.HudBackground)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = chip.label, style = chipStyle, color = Color.White)
                Text(text = chip.text, style = chipStyle, color = chip.color)
            }
        }
    }
}
