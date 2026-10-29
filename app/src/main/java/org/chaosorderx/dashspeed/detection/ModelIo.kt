package org.chaosorderx.dashspeed.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

// YOLO's customary letterbox padding grey.
private val LETTERBOX_COLOR = Color.rgb(114, 114, 114)

/**
 * Everything one loaded model reuses on every frame: float32 input and output buffers (direct, so
 * the runtime reads and writes them without copying) and, only when the camera frame is not already
 * the model's size, a resampling canvas. Nothing here allocates per frame.
 */
internal class ModelIo(val layout: ModelLayout) {
    val width = layout.width
    val height = layout.height

    private val inputBytes: ByteBuffer = nativeBuffer(width * height * 3)
    private val inputFloats = inputBytes.asFloatBuffer()
    private val staging = FloatArray(width * height * 3)

    private val outputBytes: ByteBuffer = nativeBuffer(layout.channels * layout.anchors)
    private val outputFloats = outputBytes.asFloatBuffer()

    /** The last run's output tensor, flat, in the model's own layout (see [YoloDecoder]). */
    val output = FloatArray(layout.channels * layout.anchors)

    // Resampling path (camera frame size != model size); created on first use.
    private var sourceBitmap: Bitmap? = null
    private var modelBitmap: Bitmap? = null
    private var modelCanvas: Canvas? = null
    private var modelPixels: IntArray? = null
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Writes [frame], letterboxed to the model size, into the input tensor buffer. */
    fun fill(
        frame: RgbaFrame,
        letterbox: FrameGeometry.Letterbox,
    ) {
        if (letterbox.cropW == width && letterbox.cropH == height && letterbox.cropLeft == 0) {
            // Crop is exactly the model's size: no resampling, convert the band's rows straight into staging.
            val offset = letterbox.cropTop * frame.width * RgbaFrame.BYTES_PER_PIXEL
            InputPacker.packRgba(frame.rgba, width * height, layout.nchw, staging, offset)
        } else {
            InputPacker.packArgb(resample(frame, letterbox), layout.nchw, staging)
        }
        inputFloats.rewind()
        inputFloats.put(staging)
    }

    /** Runs [interpreter] on the filled input and reads the result into [output]. */
    fun infer(interpreter: Interpreter) {
        inputBytes.rewind()
        outputBytes.rewind()
        interpreter.run(inputBytes, outputBytes)
        outputFloats.rewind()
        outputFloats.get(output)
    }

    fun release() {
        sourceBitmap?.recycle()
        modelBitmap?.recycle()
        sourceBitmap = null
        modelBitmap = null
    }

    /** Aspect-preserving resize of [frame] onto a grey model-sized canvas; returns its pixels. */
    private fun resample(
        frame: RgbaFrame,
        letterbox: FrameGeometry.Letterbox,
    ): IntArray {
        val source = sourceFor(letterbox.cropW, letterbox.cropH)
        val offset = letterbox.cropTop * frame.width * RgbaFrame.BYTES_PER_PIXEL
        val length = letterbox.cropW * letterbox.cropH * RgbaFrame.BYTES_PER_PIXEL
        source.copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba, offset, length))
        val canvas = modelCanvas ?: createModelCanvas()
        srcRect.set(0, 0, letterbox.cropW, letterbox.cropH)
        dstRect.set(
            letterbox.padX.roundToInt(),
            letterbox.padY.roundToInt(),
            (letterbox.padX + letterbox.cropW * letterbox.scale).roundToInt(),
            (letterbox.padY + letterbox.cropH * letterbox.scale).roundToInt(),
        )
        canvas.drawColor(LETTERBOX_COLOR)
        canvas.drawBitmap(source, srcRect, dstRect, scalePaint)
        val pixels = modelPixels ?: IntArray(width * height).also { modelPixels = it }
        modelBitmap?.getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    }

    private fun sourceFor(
        cropW: Int,
        cropH: Int,
    ): Bitmap {
        val current = sourceBitmap
        if (current != null && current.width == cropW && current.height == cropH) return current
        current?.recycle()
        return Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888).also { sourceBitmap = it }
    }

    private fun createModelCanvas(): Canvas {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        modelBitmap = bitmap
        return Canvas(bitmap).also { modelCanvas = it }
    }

    private companion object {
        fun nativeBuffer(floats: Int): ByteBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())
    }
}
