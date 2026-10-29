package org.chaosorderx.dashspeed.tracking

import android.graphics.RectF
import org.chaosorderx.dashspeed.detection.HoodDetector
import org.chaosorderx.dashspeed.detection.ModelConfig
import org.chaosorderx.dashspeed.overlay.SpeedAnnotation

/** The vehicle directly ahead in the driver's lane, always shown even when no speed can be estimated. */
data class LeadVehicle(
    val trackId: Int,
    val box: RectF,
    /** Rough distance from the box width (see [LeadVehicleSelector.distanceMeters]); a guess, not a measurement. */
    val distanceMeters: Float,
    val speedMph: Float?,
    val relativeMph: Float?,
    val speedIsApproximate: Boolean,
) {
    // RectF.toString() is not available in JVM unit-test stubs; exclude the box from toString.
    override fun toString() = "LeadVehicle(trackId=$trackId, distanceMeters=$distanceMeters, speedMph=$speedMph)"
}

// RectF.width() is a stub in JVM unit tests; subtract the edges instead.
private fun RectF.widthPx() = right - left

/** Chooses the lead vehicle among the tracks, with some stickiness so the highlight doesn't hop between cars. */
object LeadVehicleSelector {
    // Row (frame-normalised) of the horizon in the usual mount, measured from field logs (~row 230 of 480).
    // Vehicles whose bottom edge is above this are not on the ground plane in front of us.
    internal const val HORIZON_Y = 200f

    // A challenger must be this much wider (nearer) before it replaces the current lead. Width, not bottom row,
    // ranks nearness: far cars' bottom edges differ by only a pixel or two.
    internal const val SWITCH_WIDTH_RATIO = 1.25f

    internal const val MIN_BOX_HEIGHT_PX = 8f

    // Rough distance from apparent width: distance = focal * width / pixels. HFOV ~70° for a phone's main
    // camera gives a focal length of ~297 px in the 416 px frame space; average car width 1.8 m. Accuracy is
    // about ±25% (trucks and small cars differ) — a hint, not a measurement.
    private const val FOCAL_PX = 297f
    private const val ASSUMED_VEHICLE_WIDTH_M = 1.8f

    // A wide box on the bottom edge is the car's own hood, not a vehicle ahead (field logs: real lead cars end
    // around row 300 of 416).
    internal const val HOOD_MIN_WIDTH_FRACTION = 0.4f

    private fun isHoodLike(box: RectF): Boolean =
        HoodDetector.touchesBottom(box.bottom) &&
            box.right - box.left >= HOOD_MIN_WIDTH_FRACTION * ModelConfig.FRAME_SPACE

    fun distanceMeters(boxWidthPx: Float): Float =
        if (boxWidthPx <= 0f) Float.POSITIVE_INFINITY else FOCAL_PX * ASSUMED_VEHICLE_WIDTH_M / boxWidthPx

    /**
     * The nearest car in our own lane, or null when the lane is empty — a car in another lane is never the lead.
     *
     * @param annotations speed annotations by track id (may be missing for small or new tracks)
     * @param previousId the lead track id from the previous frame, if any
     * @param laneByTrack lane of each track relative to ours (0 = own lane), from [LaneAnalyzer]; the lane
     *   hysteresis lives there
     */
    fun select(
        tracks: List<TrackedVehicle>,
        annotations: Map<Int, SpeedAnnotation>,
        previousId: Int?,
        laneByTrack: Map<Int, Int>,
    ): LeadVehicle? {
        val inLane =
            tracks.filter {
                val box = it.boundingBox
                val tallEnough = box.bottom - box.top >= MIN_BOX_HEIGHT_PX
                laneByTrack[it.id] == 0 && tallEnough && box.bottom > HORIZON_Y && !isHoodLike(box)
            }
        val nearest = inLane.maxByOrNull { it.boundingBox.widthPx() } ?: return null
        val kept = inLane.firstOrNull { it.id == previousId }
        val chosen =
            if (kept == null || nearest.boundingBox.widthPx() >= SWITCH_WIDTH_RATIO * kept.boundingBox.widthPx()) {
                nearest
            } else {
                kept
            }
        val annotation = annotations[chosen.id]
        val box = chosen.boundingBox
        return LeadVehicle(
            trackId = chosen.id,
            box = RectF(box),
            distanceMeters = distanceMeters(box.right - box.left),
            speedMph = annotation?.speedMph,
            relativeMph = annotation?.relativeMph,
            speedIsApproximate = annotation?.approximate ?: false,
        )
    }
}
