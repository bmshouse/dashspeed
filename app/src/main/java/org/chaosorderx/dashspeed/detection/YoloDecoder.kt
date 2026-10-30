package org.chaosorderx.dashspeed.detection

/** A confident detection in model-input pixels, before NMS. */
internal data class Candidate(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val classId: Int,
    val confidence: Float,
)

/** Decodes a YOLOv8 output tensor, held flat in the model's own layout, into candidate boxes. */
internal object YoloDecoder {
    // Coordinates larger than this mean the model emits pixels rather than 0..1 fractions.
    private const val NORMALISED_COORD_LIMIT = 1.5f

    /**
     * [output] is `[channels][anchors]` flattened row-major, or `[anchors][channels]` when
     * [ModelLayout.transposedOutput]. Rows 0-3 are cx, cy, w, h; row `4 + c` is class `c`'s score.
     * Only anchors whose best score among [classIds] exceeds [threshold] are returned.
     */
    fun decode(
        output: FloatArray,
        layout: ModelLayout,
        widthPx: Float,
        heightPx: Float,
        threshold: Float,
        classIds: IntArray,
    ): List<Candidate> {
        // Step between consecutive channels / consecutive anchors in the flat array.
        val channelStep = if (layout.transposedOutput) 1 else layout.anchors
        val anchorStep = if (layout.transposedOutput) layout.channels else 1
        val fractional = maxWidth(output, layout.anchors, channelStep, anchorStep) <= NORMALISED_COORD_LIMIT
        val scaleX = if (fractional) widthPx else 1f
        val scaleY = if (fractional) heightPx else 1f
        val candidates = ArrayList<Candidate>()
        for (i in 0 until layout.anchors) {
            val base = i * anchorStep
            var bestScore = threshold
            var bestClass = -1
            for (classId in classIds) {
                val score = output[base + (CLASS_ROW_OFFSET + classId) * channelStep]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classId
                }
            }
            if (bestClass == -1) continue
            val cx = output[base] * scaleX
            val cy = output[base + channelStep] * scaleY
            val w = output[base + 2 * channelStep] * scaleX
            val h = output[base + 3 * channelStep] * scaleY
            val left = (cx - w / 2f).coerceIn(0f, widthPx)
            val top = (cy - h / 2f).coerceIn(0f, heightPx)
            val right = (cx + w / 2f).coerceIn(0f, widthPx)
            val bottom = (cy + h / 2f).coerceIn(0f, heightPx)
            if (right > left && bottom > top) candidates.add(Candidate(left, top, right, bottom, bestClass, bestScore))
        }
        return candidates
    }

    private fun maxWidth(
        output: FloatArray,
        anchors: Int,
        channelStep: Int,
        anchorStep: Int,
    ): Float {
        var max = 0f
        for (i in 0 until anchors) {
            val w = output[i * anchorStep + 2 * channelStep]
            if (w > max) max = w
        }
        return max
    }

    private const val CLASS_ROW_OFFSET = 4
}
