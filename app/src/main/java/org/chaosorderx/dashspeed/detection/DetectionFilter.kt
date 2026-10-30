package org.chaosorderx.dashspeed.detection

/** Rejects detections that are really part of the car or mount, not traffic. */
object DetectionFilter {
    /**
     * True for a box (frame-normalised, `0..FRAME_SPACE`) that should be dropped:
     * - it spans at least [ModelConfig.MAX_BOX_WIDTH_FRACTION] of the frame width — the hood and
     *   dashboard were detected as one full-width "car" that the mask alone could not remove, since
     *   most of that box is not static; or
     * - at least [ModelConfig.STATIC_MASK_OVERLAP] of its area lies on the learned static mask.
     */
    fun isStatic(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        mask: StaticMask,
    ): Boolean =
        (right - left) >= ModelConfig.MAX_BOX_WIDTH_FRACTION * ModelConfig.FRAME_SPACE ||
            mask.coverage(left, top, right, bottom) >= ModelConfig.STATIC_MASK_OVERLAP
}
