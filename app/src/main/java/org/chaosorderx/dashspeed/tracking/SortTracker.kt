package org.chaosorderx.dashspeed.tracking

import android.graphics.RectF
import org.chaosorderx.dashspeed.detection.Detection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SortTracker
    @Inject
    constructor() {
        val iouThreshold = 0.3f
        val maxAge = 5
        val minHits = 3

        private val tracks = mutableListOf<KalmanBoxTracker>()
        private var nextId = 1
        private val hungarian = HungarianAlgorithm()

        /**
         * Advances all tracks by one frame, matches detections, and returns confirmed tracks.
         *
         * A track is confirmed (returned) once it has been matched [minHits] times consecutively
         * and was matched in the current frame (timeSinceUpdate == 0). Tracks not matched for
         * more than [maxAge] frames are deleted.
         */
        fun update(
            detections: List<Detection>,
            frameTimestampMs: Long,
        ): List<TrackedVehicle> {
            val predictedBoxes = tracks.map { it.predict() }
            val matched = assign(predictedBoxes, detections)
            val matchedDetIndices = matched.map { it.second }.toSet()

            for ((trackIdx, detIdx) in matched) {
                tracks[trackIdx].update(detections[detIdx].boundingBox, frameTimestampMs)
            }

            for (i in detections.indices) {
                if (i !in matchedDetIndices) {
                    tracks.add(KalmanBoxTracker(detections[i].boundingBox, nextId++))
                }
            }

            tracks.removeAll { it.timeSinceUpdate > maxAge }

            return tracks
                .filter { it.hitStreak >= minHits && it.timeSinceUpdate == 0 }
                .map { tracker ->
                    TrackedVehicle(
                        id = tracker.id,
                        boundingBox = tracker.getState(),
                        history = tracker.history,
                    )
                }
        }

        private fun assign(
            predictions: List<RectF>,
            detections: List<Detection>,
        ): List<Pair<Int, Int>> {
            if (predictions.isEmpty() || detections.isEmpty()) return emptyList()
            val cost =
                Array(predictions.size) { i ->
                    FloatArray(detections.size) { j -> 1f - iou(predictions[i], detections[j].boundingBox) }
                }
            val assignment = hungarian.solve(cost)
            val matched = mutableListOf<Pair<Int, Int>>()
            for ((trackIdx, detIdx) in assignment.withIndex()) {
                if (detIdx == -1) continue
                if (1f - cost[trackIdx][detIdx] >= iouThreshold) matched.add(trackIdx to detIdx)
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
