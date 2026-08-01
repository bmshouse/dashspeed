package org.chaosorderx.dashspeed.speed

import android.graphics.RectF
import org.chaosorderx.dashspeed.overlay.SpeedAnnotation
import org.chaosorderx.dashspeed.tracking.PositionSample
import org.chaosorderx.dashspeed.tracking.TrackedVehicle
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SpeedEstimator
    @Inject
    constructor(
        private val calibrator: HomographyCalibrator,
    ) {
        private val emaAlpha = 0.3f
        private val emaState = mutableMapOf<Int, Float>()

        @Volatile private var matrixJson = ""

        companion object {
            // Boxes shorter than this (model pixels) are too far away for reliable estimates.
            internal const val MIN_BOX_HEIGHT_PX = 20f

            // Minimum history before an estimate is emitted. Frame-pair differencing amplifies
            // one pixel of box jitter into tens of m/s; a windowed fit divides that noise down.
            internal const val MIN_SAMPLES = 5
            internal const val MIN_WINDOW_SEC = 0.15f

            // Symmetric plausibility gate on *relative* speed (~±100 mph). Wide enough that a
            // stopped car ahead at highway ego speed still reads 0 mph, but rejects oncoming
            // traffic and absurd fits. Symmetry matters: the old gate on absolute ∈ [0, max]
            // rejected noise spikes below far more often than above at highway ego speeds,
            // which biased jittery (adjacent-lane) tracks upward.
            internal const val MAX_RELATIVE_MPS = 45f

            // Sanity ceiling on the smoothed absolute speed (~150 mph).
            internal const val MAX_ABSOLUTE_MPS = 67f

            // A truly stationary lead car at highway ego speed computes to exactly 0 m/s in
            // theory, but the Theil–Sen median (an accumulation of pairwise-slope divisions)
            // can round a hair below zero. Treat anything within this noise band as 0 rather
            // than rejecting the frame; genuinely negative results (sensor/detection error)
            // beyond the band are still nulled out below.
            private const val ZERO_NOISE_FLOOR_MPS = -0.5f

            private const val MPS_TO_MPH = 2.237f
        }

        fun setCalibration(json: String) {
            matrixJson = json
        }

        fun cleanupTracks(activeIds: Set<Int>) {
            emaState.keys.retainAll(activeIds)
        }

        fun estimate(
            track: TrackedVehicle,
            egoSpeedMps: Float,
        ): SpeedAnnotation? {
            val history = track.history
            // Direct field subtraction — RectF.height() is stubbed in JVM unit tests.
            val usable =
                track.boundingBox.bottom - track.boundingBox.top >= MIN_BOX_HEIGHT_PX &&
                    history.size >= MIN_SAMPLES &&
                    (history.last().timestampMs - history.first().timestampMs) / 1_000f >= MIN_WINDOW_SEC
            val relativeMps = if (usable) relativeSpeedMps(history) else null
            if (relativeMps == null || abs(relativeMps) > MAX_RELATIVE_MPS) return null

            val smoothedMps = applyEma(track.id, egoSpeedMps + relativeMps)
            return if (smoothedMps < ZERO_NOISE_FLOOR_MPS || smoothedMps > MAX_ABSOLUTE_MPS) {
                null
            } else {
                val displayMps = smoothedMps.coerceAtLeast(0f)
                SpeedAnnotation(
                    trackId = track.id,
                    modelBox = RectF(track.boundingBox),
                    speedMph = displayMps * MPS_TO_MPH,
                    relativeMph = (displayMps - egoSpeedMps) * MPS_TO_MPH,
                )
            }
        }

        /**
         * Relative speed (m/s, positive = pulling away) as the Theil–Sen slope of ground
         * distance over time across the track's history window.
         *
         * Each sample's box-bottom row is projected to metres ahead via the calibrator, then
         * the median of all pairwise slopes is taken. The median makes the estimate robust to
         * the box-jitter outliers that plague oblique (adjacent-lane) detections, which a
         * two-frame difference amplifies into wild speed swings.
         */
        internal fun relativeSpeedMps(history: List<PositionSample>): Float? {
            val json = matrixJson
            val distances = history.map { calibrator.groundDistanceMeters(it.bottomCenter.y, json) }
            val slopes = ArrayList<Float>(history.size * (history.size - 1) / 2)
            for (i in history.indices) {
                for (j in i + 1 until history.size) {
                    val dtSec = (history[j].timestampMs - history[i].timestampMs) / 1_000f
                    if (dtSec > 1e-3f) slopes.add((distances[j] - distances[i]) / dtSec)
                }
            }
            if (slopes.isEmpty()) return null
            slopes.sort()
            val mid = slopes.size / 2
            return if (slopes.size % 2 == 1) slopes[mid] else (slopes[mid - 1] + slopes[mid]) / 2f
        }

        private fun applyEma(
            trackId: Int,
            rawMps: Float,
        ): Float {
            val prev = emaState[trackId]
            val smoothed = if (prev == null) rawMps else emaAlpha * rawMps + (1f - emaAlpha) * prev
            emaState[trackId] = smoothed
            return smoothed
        }
    }
