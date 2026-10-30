package org.chaosorderx.dashspeed.detection

/** Converts 8-bit RGB(A) pixels to the model's float32 `[0, 1]` input, interleaved (NHWC) or planar (NCHW). */
internal object InputPacker {
    private val UNIT = FloatArray(256) { it / 255f }

    /** From tightly packed RGBA bytes starting at byte [offset]. [out] must hold `pixelCount * 3` floats. */
    fun packRgba(
        rgba: ByteArray,
        pixelCount: Int,
        nchw: Boolean,
        out: FloatArray,
        offset: Int = 0,
    ) {
        if (nchw) {
            for (i in 0 until pixelCount) {
                val o = offset + i * RgbaFrame.BYTES_PER_PIXEL
                out[i] = UNIT[rgba[o].toInt() and 0xFF]
                out[pixelCount + i] = UNIT[rgba[o + 1].toInt() and 0xFF]
                out[2 * pixelCount + i] = UNIT[rgba[o + 2].toInt() and 0xFF]
            }
        } else {
            for (i in 0 until pixelCount) {
                val o = offset + i * RgbaFrame.BYTES_PER_PIXEL
                val d = i * 3
                out[d] = UNIT[rgba[o].toInt() and 0xFF]
                out[d + 1] = UNIT[rgba[o + 1].toInt() and 0xFF]
                out[d + 2] = UNIT[rgba[o + 2].toInt() and 0xFF]
            }
        }
    }

    /** From Android `ARGB` ints (`Bitmap.getPixels`). */
    fun packArgb(
        pixels: IntArray,
        nchw: Boolean,
        out: FloatArray,
    ) {
        val n = pixels.size
        if (nchw) {
            for (i in 0 until n) {
                val p = pixels[i]
                out[i] = UNIT[(p shr 16) and 0xFF]
                out[n + i] = UNIT[(p shr 8) and 0xFF]
                out[2 * n + i] = UNIT[p and 0xFF]
            }
        } else {
            for (i in 0 until n) {
                val p = pixels[i]
                val d = i * 3
                out[d] = UNIT[(p shr 16) and 0xFF]
                out[d + 1] = UNIT[(p shr 8) and 0xFF]
                out[d + 2] = UNIT[p and 0xFF]
            }
        }
    }
}
