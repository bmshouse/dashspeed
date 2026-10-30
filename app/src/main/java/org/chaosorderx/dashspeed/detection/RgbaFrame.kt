package org.chaosorderx.dashspeed.detection

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * One camera frame as tightly packed RGBA bytes (`R, G, B, A` per pixel, rows without padding).
 * The backing array is owned and reused by the caller, so the analysis path allocates nothing per frame.
 */
class RgbaFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
) {
    init {
        require(rgba.size >= width * height * BYTES_PER_PIXEL) { "rgba array too small for ${width}x$height" }
    }

    companion object {
        const val BYTES_PER_PIXEL = 4

        /** Copies [bitmap] into a frame, reusing [reuse]'s array when it is big enough. */
        fun fromBitmap(
            bitmap: Bitmap,
            reuse: RgbaFrame?,
        ): RgbaFrame {
            val isArgb = bitmap.config == Bitmap.Config.ARGB_8888
            val source = if (isArgb) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val needed = source.width * source.height * BYTES_PER_PIXEL
            val array = reuse?.rgba?.takeIf { it.size >= needed } ?: ByteArray(needed)
            source.copyPixelsToBuffer(ByteBuffer.wrap(array))
            if (source !== bitmap) source.recycle()
            return RgbaFrame(source.width, source.height, array)
        }
    }
}
