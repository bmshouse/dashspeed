package org.chaosorderx.dashspeed.tracking

import android.graphics.RectF
import org.chaosorderx.dashspeed.detection.Detection
import org.chaosorderx.dashspeed.detection.ModelConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SORT-style multi-object tracker with ByteTrack-style two-threshold association.
 *
 * Detections at or above [startConfidence] ("high") may both extend and start tracks. Weaker
 * detections ("low", down to the detector's floor) may only extend tracks that high detections left
 * unmatched, which keeps a far vehicle's track alive through frames where its confidence dips just
 * under the start threshold, without letting weak boxes create new tracks (false positives).
 */
@Singleton
class SortTracker
    @Inject
    constructor() {
        val iouThreshold = 0.3f
        val maxAge = 5
        val minHits = 3

        /** A confirmed track is still reported (at its predicted box) for this many missed frames. */
        val coastFrames = 2

        val startConfidence = ModelConfig.TRACK_START_CONFIDENCE

        private val tracks = mutableListOf<KalmanBoxTracker>()
        private var nextId = 1
        private val hungarian = HungarianAlgorithm()

        /**
         * Advances all tracks by one frame, matches detections, and returns confirmed tracks.
         *
         * A track is confirmed once it has been matched [minHits] times. It is reported while it is
         * matched, and for up to [coastFrames] further frames at its predicted position, so a single
         * missed detection doesn't make its box blink. Tracks not matched for more than [maxAge]
         * frames are deleted.
         */
        fun update(
            detections: List<Detection>,
            frameTimestampMs: Long,
        ): List<TrackedVehicle> {
            val predictedBoxes = tracks.map { it.predict() }
            val (high, low) = detections.indices.partition { detections[it].confidence >= startConfidence }

            val firstPass = assign(predictedBoxes, tracks.indices.toList(), detections, high)
            val matchedTracks = firstPass.map { it.first }.toSet()
            val leftover = tracks.indices.filter { it !in matchedTracks }
            val secondPass = assign(predictedBoxes, leftover, detections, low)

            for ((trackIdx, detIdx) in firstPass + secondPass) {
                val detection = detections[detIdx]
                // A box cut off by the crop edge has an unreliable bottom row: keep tracking it, but
                // don't let it pollute the speed history.
                val record = !detection.bottomClipped
                tracks[trackIdx].update(detection.boundingBox, frameTimestampMs, recordSample = record)
            }

            val matchedHigh = firstPass.map { it.second }.toSet()
            for (i in high) {
                if (i !in matchedHigh) tracks.add(KalmanBoxTracker(detections[i].boundingBox, nextId++))
            }

            tracks.removeAll { it.timeSinceUpdate > maxAge }

            return tracks
                .filter { it.hits >= minHits && it.timeSinceUpdate <= coastFrames }
                .map { tracker ->
                    TrackedVehicle(
                        id = tracker.id,
                        boundingBox = tracker.getState(),
                        history = tracker.history,
                        coasting = tracker.timeSinceUpdate > 0,
                    )
                }
        }

        /** Hungarian match of the given track indices to the given detection indices; returns global index pairs. */
        private fun assign(
            predictions: List<RectF>,
            trackIndices: List<Int>,
            detections: List<Detection>,
            detectionIndices: List<Int>,
        ): List<Pair<Int, Int>> {
            if (trackIndices.isEmpty() || detectionIndices.isEmpty()) return emptyList()
            val cost =
                Array(trackIndices.size) { i ->
                    FloatArray(detectionIndices.size) { j ->
                        1f - iou(predictions[trackIndices[i]], detections[detectionIndices[j]].boundingBox)
                    }
                }
            val assignment = hungarian.solve(cost)
            val matched = mutableListOf<Pair<Int, Int>>()
            for ((row, col) in assignment.withIndex()) {
                if (col == -1) continue
                if (1f - cost[row][col] >= iouThreshold) matched.add(trackIndices[row] to detectionIndices[col])
            }
            return matched
        }

        private fun iou(
            a: RectF,
            b: RectF,
        ): Float {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val right = minOf(a.right, b.right)
            val bottom = minOf(a.bottom, b.bottom)
            val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
            val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - intersection
            return if (union <= 0f) 0f else intersection / union
        }
    }
