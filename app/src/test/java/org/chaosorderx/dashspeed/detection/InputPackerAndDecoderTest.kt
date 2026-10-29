package org.chaosorderx.dashspeed.detection

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class InputPackerAndDecoderTest : FreeSpec({
    val tol = 0.001f

    "InputPacker" - {
        // Two pixels: (R,G,B,A) = (255, 0, 51, 255) and (0, 102, 255, 255).
        val rgba = byteArrayOf(-1, 0, 51, -1, 0, 102, -1, -1)

        "NHWC interleaves channels per pixel" {
            val out = FloatArray(6)
            InputPacker.packRgba(rgba, pixelCount = 2, nchw = false, out = out)
            out[0] shouldBe (1f plusOrMinus tol)
            out[1] shouldBe (0f plusOrMinus tol)
            out[2] shouldBe (0.2f plusOrMinus tol)
            out[3] shouldBe (0f plusOrMinus tol)
            out[4] shouldBe (0.4f plusOrMinus tol)
            out[5] shouldBe (1f plusOrMinus tol)
        }

        "NCHW writes whole R, G, B planes" {
            val out = FloatArray(6)
            InputPacker.packRgba(rgba, pixelCount = 2, nchw = true, out = out)
            listOf(out[0], out[1]) shouldBe listOf(1f, 0f)
            out[2] shouldBe (0f plusOrMinus tol)
            out[3] shouldBe (0.4f plusOrMinus tol)
            out[4] shouldBe (0.2f plusOrMinus tol)
            out[5] shouldBe (1f plusOrMinus tol)
        }

        "ARGB ints give the same result as the equivalent RGBA bytes" {
            val argb = intArrayOf(0xFF_FF_00_33.toInt(), 0xFF_00_66_FF.toInt())
            val fromInts = FloatArray(6)
            val fromBytes = FloatArray(6)
            InputPacker.packArgb(argb, nchw = true, out = fromInts)
            InputPacker.packRgba(rgba, pixelCount = 2, nchw = true, out = fromBytes)
            fromInts.toList() shouldBe fromBytes.toList()
        }
    }

    "YoloDecoder" - {
        val classIds = intArrayOf(2, 7)

        // 3 anchors, 12 channels (4 box + classes 0..7). Anchor 1 is a confident car, anchor 2 a weak truck.
        fun channelMajor(scale: Float): FloatArray {
            val anchors = 3
            val out = FloatArray(12 * anchors)

            fun set(
                channel: Int,
                anchor: Int,
                value: Float,
            ) {
                out[channel * anchors + anchor] = value
            }
            set(0, 1, 0.5f * scale)
            set(1, 1, 0.5f * scale)
            set(2, 1, 0.2f * scale)
            set(3, 1, 0.1f * scale)
            set(4 + 2, 1, 0.9f)
            set(0, 2, 0.25f * scale)
            set(1, 2, 0.25f * scale)
            set(2, 2, 0.1f * scale)
            set(3, 2, 0.1f * scale)
            set(4 + 7, 2, 0.2f)
            return out
        }

        val layout =
            ModelLayout(width = 100, height = 50, nchw = false, transposedOutput = false, channels = 12, anchors = 3)

        "decodes fractional coordinates into model pixels" {
            val found = YoloDecoder.decode(channelMajor(1f), layout, 100f, 50f, threshold = 0.3f, classIds = classIds)
            found.size shouldBe 1
            val car = found.single()
            car.classId shouldBe 2
            car.confidence shouldBe (0.9f plusOrMinus tol)
            car.left shouldBe (40f plusOrMinus tol)
            car.right shouldBe (60f plusOrMinus tol)
            car.top shouldBe (22.5f plusOrMinus tol)
            car.bottom shouldBe (27.5f plusOrMinus tol)
        }

        "a lower threshold also returns the weak detection" {
            val found = YoloDecoder.decode(channelMajor(1f), layout, 100f, 50f, threshold = 0.1f, classIds = classIds)
            found.map { it.classId }.sorted() shouldBe listOf(2, 7)
        }

        "treats large coordinates as already being pixels" {
            val found = YoloDecoder.decode(channelMajor(100f), layout, 100f, 50f, threshold = 0.3f, classIds = classIds)
            found.single().left shouldBe (40f plusOrMinus tol)
        }

        "reads the transposed layout identically" {
            val flat = channelMajor(1f)
            val transposed = FloatArray(flat.size)
            for (c in 0 until 12) for (a in 0 until 3) transposed[a * 12 + c] = flat[c * 3 + a]
            val t = layout.copy(transposedOutput = true)
            val a = YoloDecoder.decode(flat, layout, 100f, 50f, 0.3f, classIds)
            val b = YoloDecoder.decode(transposed, t, 100f, 50f, 0.3f, classIds)
            b shouldBe a
        }

        "ignores classes that are not vehicles" {
            val noVehicles = YoloDecoder.decode(channelMajor(1f), layout, 100f, 50f, 0.3f, intArrayOf(0, 1))
            noVehicles.size shouldBe 0
        }
    }
})
