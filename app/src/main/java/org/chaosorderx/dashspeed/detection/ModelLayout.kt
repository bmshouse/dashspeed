package org.chaosorderx.dashspeed.detection

/**
 * Tensor layout of a YOLOv8 detection model, inferred from its input/output shapes so different
 * exporters work without code changes:
 * - input `[1, H, W, 3]` (legacy `tflite` export, NHWC) or `[1, 3, H, W]` (newer `litert` export, NCHW);
 * - output `[1, 4 + classes, anchors]` or its transpose `[1, anchors, 4 + classes]`.
 */
data class ModelLayout(
    val width: Int,
    val height: Int,
    val nchw: Boolean,
    val transposedOutput: Boolean,
    val channels: Int,
    val anchors: Int,
) {
    companion object {
        /** Null if the shapes are not a recognisable single-image detection model. */
        fun parse(
            inShape: IntArray,
            outShape: IntArray,
        ): ModelLayout? {
            val input = parseInput(inShape)
            val outputOk = outShape.size == 3 && outShape[0] == 1
            if (input == null || !outputOk) return null
            val transposed = outShape[1] > outShape[2]
            val channels = if (transposed) outShape[2] else outShape[1]
            val anchors = if (transposed) outShape[1] else outShape[2]
            // Class scores for every vehicle class id must exist after the 4 box rows.
            val classesPresent = channels > 4 + (ModelConfig.VEHICLE_CLASS_IDS.maxOrNull() ?: 0)
            return if (classesPresent) {
                ModelLayout(input.width, input.height, input.nchw, transposed, channels, anchors)
            } else {
                null
            }
        }

        private data class Input(val width: Int, val height: Int, val nchw: Boolean)

        private fun parseInput(shape: IntArray): Input? =
            when {
                shape.size != 4 || shape[0] != 1 -> null
                shape[3] == RGB_CHANNELS -> Input(width = shape[2], height = shape[1], nchw = false)
                shape[1] == RGB_CHANNELS -> Input(width = shape[3], height = shape[2], nchw = true)
                else -> null
            }

        private const val RGB_CHANNELS = 3
    }
}
