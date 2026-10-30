package org.chaosorderx.dashspeed.detection

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle of the camera frame, in the frame's own pixels. */
data class FrameCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * Coordinate conversions between the camera frame, the letterboxed model input, and the screen.
 *
 * Downstream code (tracker, speed estimator, overlay) works in **frame-normalised space**: the
 * full camera frame mapped onto `0..FRAME_SPACE` on both axes. That is the same space the old
 * stretched-resize produced, so the homography/speed maths is unchanged, while the model itself
 * sees an aspect-correct (letterboxed) image — of the whole frame, or of a horizontal band of it.
 */
object FrameGeometry {
    // A model this much wider than the camera frame's aspect ratio is a "band" model: it sees a
    // horizontal strip of the frame, not the whole frame.
    private const val BAND_ASPECT_FACTOR = 1.3f

    /** Placement of a [cropW]×[cropH] region of a [frameW]×[frameH] frame inside the model input. */
    data class Letterbox(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val frameW: Int,
        val frameH: Int,
        val cropLeft: Int = 0,
        val cropTop: Int = 0,
        val cropW: Int = frameW,
        val cropH: Int = frameH,
    ) {
        /** True when only part of the frame is shown to the model. */
        val isCropped: Boolean get() = cropW != frameW || cropH != frameH

        /** Model-input y of the cropped image's bottom edge (below it is padding). */
        val imageBottom: Float get() = padY + cropH * scale

        /** Model-input x → frame-normalised x (`0..FRAME_SPACE`). */
        fun toNormX(x: Float): Float {
            val frameX = cropLeft + (x - padX) / scale
            return (frameX / frameW).coerceIn(0f, 1f) * ModelConfig.FRAME_SPACE
        }

        /** Model-input y → frame-normalised y (`0..FRAME_SPACE`). */
        fun toNormY(y: Float): Float {
            val frameY = cropTop + (y - padY) / scale
            return (frameY / frameH).coerceIn(0f, 1f) * ModelConfig.FRAME_SPACE
        }
    }

    fun letterbox(
        frameW: Int,
        frameH: Int,
        modelW: Int,
        modelH: Int,
        crop: FrameCrop = FrameCrop(0, 0, frameW, frameH),
    ): Letterbox {
        val scale = min(modelW.toFloat() / crop.width, modelH.toFloat() / crop.height)
        return Letterbox(
            scale = scale,
            padX = (modelW - crop.width * scale) / 2f,
            padY = (modelH - crop.height * scale) / 2f,
            frameW = frameW,
            frameH = frameH,
            cropLeft = crop.left,
            cropTop = crop.top,
            cropW = crop.width,
            cropH = crop.height,
        )
    }

    /** Whether a [modelW]×[modelH] model is a band model (much wider than the frame's aspect ratio). */
    fun isBandModel(
        modelW: Int,
        modelH: Int,
    ): Boolean = modelW.toFloat() / modelH > ModelConfig.FRAME_ASPECT * BAND_ASPECT_FACTOR

    /**
     * The strip of the frame a band model should see: full width, as tall as the model's aspect
     * ratio implies, centred on [centerFrac] (0..1 of the frame height) and kept inside the frame.
     * A model whose aspect ratio needs the whole frame (or more) gets the whole frame.
     */
    fun bandCrop(
        frameW: Int,
        frameH: Int,
        modelW: Int,
        modelH: Int,
        centerFrac: Float,
    ): FrameCrop {
        val bandH = (frameW.toLong() * modelH / modelW).toInt()
        return if (bandH >= frameH) FrameCrop(0, 0, frameW, frameH) else stripCrop(frameW, frameH, bandH, centerFrac)
    }

    /** A full-width strip of [rows] rows centred on [centerFrac] of the frame height, kept inside the frame. */
    fun stripCrop(
        frameW: Int,
        frameH: Int,
        rows: Int,
        centerFrac: Float,
    ): FrameCrop {
        val top = (centerFrac * frameH - rows / 2f).roundToInt().coerceIn(0, frameH - rows)
        return FrameCrop(0, top, frameW, rows)
    }

    /**
     * How `PreviewView`'s default FILL_CENTER draws a frame of [frameAspect] (w/h) in a view:
     * scaled to cover the view, centred, overflow cropped. [dispW]/[dispH] are the full frame's
     * on-screen size; [offX]/[offY] its (possibly negative) top-left.
     */
    data class FillCenter(
        val dispW: Float,
        val dispH: Float,
        val offX: Float,
        val offY: Float,
    ) {
        /** Frame-normalised x (`0..FRAME_SPACE`) → view x. */
        fun toViewX(x: Float): Float = x / ModelConfig.FRAME_SPACE * dispW + offX

        /** Frame-normalised y (`0..FRAME_SPACE`) → view y. */
        fun toViewY(y: Float): Float = y / ModelConfig.FRAME_SPACE * dispH + offY

        /** View x → static-mask column, or -1 if outside the frame. */
        fun colAt(x: Float): Int = cellIndex((x - offX) / dispW, StaticMask.COLS)

        /** View y → static-mask row, or -1 if outside the frame. */
        fun rowAt(y: Float): Int = cellIndex((y - offY) / dispH, StaticMask.ROWS)

        private fun cellIndex(
            fraction: Float,
            count: Int,
        ): Int = if (fraction < 0f || fraction >= 1f) -1 else (fraction * count).toInt()
    }

    fun fillCenter(
        viewW: Float,
        viewH: Float,
        frameAspect: Float = ModelConfig.FRAME_ASPECT,
    ): FillCenter {
        val scale = max(viewW / frameAspect, viewH)
        val dispW = scale * frameAspect
        val dispH = scale
        return FillCenter(dispW, dispH, (viewW - dispW) / 2f, (viewH - dispH) / 2f)
    }
}
