package org.chaosorderx.dashspeed.detection

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the car's own hood/dashboard when the detector mistakes it for a vehicle.
 *
 * A real vehicle never sits on the bottom edge of the frame for long while we drive, but the hood does:
 * a wide box touching the bottom edge that stays in the same place for [PERSIST_MS] of driving is flagged
 * as a hood [Zone]. Later detections that lie inside a zone are dropped, and the caller adds each new zone
 * to the static mask (which also persists it). Analysis-thread only.
 */
@Singleton
class HoodDetector
    @Inject
    constructor() {
        /** A frame-normalised rectangle. */
        data class Zone(
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
        )

        private class Candidate(
            var zone: Zone,
            val firstMs: Long,
            var lastMs: Long,
        )

        private val candidates = mutableListOf<Candidate>()
        private val _zones = mutableListOf<Zone>()
        val zones: List<Zone> get() = _zones

        private val clippedEdges = ClippedEdgeFilter()

        fun reset() {
            candidates.clear()
            _zones.clear()
            clippedEdges.reset()
        }

        /**
         * Returns [detections] without hood boxes, plus any zone flagged on this frame.
         * Candidates only accumulate while [moving]; a stopped car may legitimately be right behind another.
         */
        fun filter(
            detections: List<Detection>,
            moving: Boolean,
            timestampMs: Long,
        ): Pair<List<Detection>, List<Zone>> {
            val flagged = mutableListOf<Zone>()
            candidates.removeAll { timestampMs - it.lastMs > FORGET_MS }
            val kept = detections.filter { keep(it, moving, timestampMs, flagged) }
            return kept to flagged
        }

        private fun keep(
            d: Detection,
            moving: Boolean,
            timestampMs: Long,
            flagged: MutableList<Zone>,
        ): Boolean {
            if (clippedEdges.applies(d)) return keepClipped(d, moving, timestampMs)
            val zone = d.boundingBox.toZone()
            return !zone.isHoodShaped() || keepBottomEdgeBox(zone, moving, timestampMs, flagged)
        }

        private fun keepClipped(
            d: Detection,
            moving: Boolean,
            timestampMs: Long,
        ): Boolean {
            if (clippedEdges.isHood(d)) return false
            if (moving) clippedEdges.observe(d, timestampMs)
            return true
        }

        private fun keepBottomEdgeBox(
            zone: Zone,
            moving: Boolean,
            timestampMs: Long,
            flagged: MutableList<Zone>,
        ): Boolean {
            if (_zones.any { zone.matches(it) }) return false
            if (moving) trackCandidate(zone, timestampMs)?.let { flagged += it }
            return true
        }

        /** Advances the candidate matching [zone]; returns a new hood zone once it has persisted long enough. */
        private fun trackCandidate(
            zone: Zone,
            timestampMs: Long,
        ): Zone? {
            val candidate = candidates.firstOrNull { iou(zone, it.zone) >= MATCH_IOU }
            if (candidate == null) {
                candidates += Candidate(zone, timestampMs, timestampMs)
                return null
            }
            candidate.zone = zone
            candidate.lastMs = timestampMs
            if (timestampMs - candidate.firstMs < PERSIST_MS) return null
            candidates.remove(candidate)
            _zones += zone
            return zone
        }

        companion object {
            const val PERSIST_MS = 3_000L
            const val FORGET_MS = 1_500L
            const val MATCH_IOU = 0.5f

            /** A box whose bottom lies within this many frame px of the bottom edge touches it. */
            const val EDGE_PX = 12f
            private const val MIN_WIDTH_FRACTION = 0.2f
            private const val CONTAINED = 0.8f

            /** True for a box on the bottom edge — never a vehicle ahead on the road. */
            fun touchesBottom(bottom: Float): Boolean = bottom >= ModelConfig.FRAME_SPACE - EDGE_PX

            private fun Zone.isHoodShaped(): Boolean {
                val minWidth = MIN_WIDTH_FRACTION * ModelConfig.FRAME_SPACE
                return touchesBottom(bottom) && right - left >= minWidth
            }

            private fun Zone.matches(zone: Zone): Boolean {
                val area = (right - left) * (bottom - top)
                val contained = if (area <= 0f) 0f else intersection(this, zone) / area
                return iou(this, zone) >= MATCH_IOU || contained >= CONTAINED
            }

            private fun intersection(
                a: Zone,
                b: Zone,
            ): Float {
                val width = min(a.right, b.right) - max(a.left, b.left)
                val height = min(a.bottom, b.bottom) - max(a.top, b.top)
                return max(0f, width) * max(0f, height)
            }

            private fun iou(
                a: Zone,
                b: Zone,
            ): Float {
                val inter = intersection(a, b)
                val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
                return if (union <= 0f) 0f else inter / union
            }
        }
    }

private fun android.graphics.RectF.toZone() = HoodDetector.Zone(left, top, right, bottom)
