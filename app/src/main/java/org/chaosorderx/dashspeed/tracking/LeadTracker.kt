package org.chaosorderx.dashspeed.tracking

import org.chaosorderx.dashspeed.overlay.SpeedAnnotation

/**
 * Adds time-based stability on top of [LeadVehicleSelector], which only looks at one frame:
 * - a challenger must be preferred for [SWITCH_FRAMES] consecutive frames before it takes over from a lead
 *   that is still valid, so two similar cars do not trade the highlight every frame;
 * - when no lead is found, the last one is held for [HOLD_MS] so a brief detection gap does not blink it off.
 *
 * Call once per camera frame, in frame order. Not thread-safe.
 */
class LeadTracker {
    private var current: LeadVehicle? = null
    private var lastFreshMs = 0L
    private var challengerId: Int? = null
    private var challengerFrames = 0

    fun update(
        tracks: List<TrackedVehicle>,
        annotations: Map<Int, SpeedAnnotation>,
        timestampMs: Long,
        laneByTrack: Map<Int, Int>,
    ): LeadVehicle? {
        val previous = current
        val picked = LeadVehicleSelector.select(tracks, annotations, previous?.trackId, laneByTrack)
        val fresh =
            when {
                picked == null -> null
                previous == null || picked.trackId == previous.trackId -> picked.also { clearChallenger() }
                else -> keepOrSwitch(previous, picked, tracks, annotations, laneByTrack)
            }
        if (fresh != null) lastFreshMs = timestampMs
        current =
            when {
                fresh != null -> fresh
                previous != null && timestampMs - lastFreshMs <= HOLD_MS -> previous
                else -> null
            }
        return current
    }

    /** The old lead if it is still valid and the challenger has not yet persisted long enough, else the challenger. */
    private fun keepOrSwitch(
        previous: LeadVehicle,
        picked: LeadVehicle,
        tracks: List<TrackedVehicle>,
        annotations: Map<Int, SpeedAnnotation>,
        laneByTrack: Map<Int, Int>,
    ): LeadVehicle {
        val stillValid =
            LeadVehicleSelector.select(
                tracks.filter { it.id == previous.trackId },
                annotations,
                previous.trackId,
                laneByTrack,
            )
        if (stillValid == null) {
            clearChallenger()
            return picked
        }
        challengerFrames = if (challengerId == picked.trackId) challengerFrames + 1 else 1
        challengerId = picked.trackId
        if (challengerFrames < SWITCH_FRAMES) return stillValid
        clearChallenger()
        return picked
    }

    private fun clearChallenger() {
        challengerId = null
        challengerFrames = 0
    }

    companion object {
        /** ≈ 0.5 s at the 15 fps analysis cap. */
        const val SWITCH_FRAMES = 8
        const val HOLD_MS = 700L
    }
}
