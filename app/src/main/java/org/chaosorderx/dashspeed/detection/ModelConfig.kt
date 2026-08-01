package org.chaosorderx.dashspeed.detection

import android.graphics.RectF

object ModelConfig {
    const val INPUT_SIZE = 416
    const val CONFIDENCE_THRESHOLD = 0.4f
    const val NMS_IOU_THRESHOLD = 0.45f
    const val MODEL_FILE = "yolov8n_int8.tflite"

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
