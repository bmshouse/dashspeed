package org.chaosorderx.dashspeed.detection

import android.graphics.RectF

object ModelConfig {
    // Extent of the frame-normalised coordinate space: the whole camera frame maps to 0..FRAME_SPACE on
    // both axes. Tracker, speed maths (homography), static mask, hood filter and overlay all work in it.
    // It is independent of the model's input size, which is read from the model file at load time.
    const val FRAME_SPACE = 416

    // The detector reports everything at or above this floor; how much to trust each box is the tracker's
    // business. Boxes at or above TRACK_START_CONFIDENCE may start a track; weaker ones only extend tracks.
    const val DETECTION_FLOOR = 0.15f
    const val TRACK_START_CONFIDENCE = 0.35f
    const val NMS_IOU_THRESHOLD = 0.45f

    // Every .tflite in the app's assets is a candidate; at first run (and whenever the set of files, the
    // app version or the device changes) each is benchmarked and the highest-resolution one whose p90
    // inference time is within this budget is used (the fastest if none is).
    const val MODEL_BUDGET_P90_MS = 50f

    // Analysis runs at most this often. Speed estimation needs ~150 ms of history, not 30 fps, and
    // idling the GPU between frames is what keeps the phone from throttling.
    const val MAX_ANALYSIS_FPS = 15

    // Runtime governor: step to a smaller model when the median per-frame time stays above this
    // (just under the 66.7 ms frame period at MAX_ANALYSIS_FPS, i.e. the cap can no longer be held).
    const val GOVERNOR_DOWN_MEDIAN_MS = 62f

    // Camera frames are requested at 4:3 (landscape); Preview and ImageAnalysis share it so
    // the overlay can map detections onto the on-screen preview.
    const val FRAME_ASPECT = 4f / 3f

    // A detection is dropped when at least this much of its box lies on the learned static mask.
    const val STATIC_MASK_OVERLAP = 0.7f

    // A box at least this wide (fraction of frame width) is the hood/dashboard, not a vehicle.
    const val MAX_BOX_WIDTH_FRACTION = 0.85f

    val VEHICLE_CLASS_IDS = setOf(2, 3, 5, 7) // car, motorcycle, bus, truck (COCO)

    fun nonMaxSuppression(
        detections: List<Detection>,
        iouThreshold: Float = NMS_IOU_THRESHOLD,
    ): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best.boundingBox, it.boundingBox) > iouThreshold }
        }
        return result
    }

    private fun iou(
        a: RectF,
        b: RectF,
    ): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        if (interArea == 0f) return 0f
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return interArea / unionArea
    }
}
