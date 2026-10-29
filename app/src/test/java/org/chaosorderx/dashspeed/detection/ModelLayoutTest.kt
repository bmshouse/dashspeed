package org.chaosorderx.dashspeed.detection

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ModelLayoutTest : FreeSpec({
    "legacy tflite export: NHWC input, [1,84,N] output" {
        val layout = ModelLayout.parse(intArrayOf(1, 416, 416, 3), intArrayOf(1, 84, 3549))!!
        layout shouldBe ModelLayout(416, 416, nchw = false, transposedOutput = false, channels = 84, anchors = 3549)
    }

    "rectangular NHWC input reports width and height the right way round" {
        val layout = ModelLayout.parse(intArrayOf(1, 480, 640, 3), intArrayOf(1, 84, 6300))!!
        layout.width shouldBe 640
        layout.height shouldBe 480
        layout.nchw shouldBe false
    }

    "litert export: NCHW input" {
        val layout = ModelLayout.parse(intArrayOf(1, 3, 480, 640), intArrayOf(1, 84, 6300))!!
        layout.width shouldBe 640
        layout.height shouldBe 480
        layout.nchw shouldBe true
        layout.transposedOutput shouldBe false
    }

    "transposed [1,N,84] output is recognised" {
        val layout = ModelLayout.parse(intArrayOf(1, 3, 384, 512), intArrayOf(1, 4032, 84))!!
        layout.transposedOutput shouldBe true
        layout.channels shouldBe 84
        layout.anchors shouldBe 4032
    }

    "rejects input that is not a single RGB image" {
        ModelLayout.parse(intArrayOf(1, 416, 416, 1), intArrayOf(1, 84, 3549)).shouldBeNull()
        ModelLayout.parse(intArrayOf(2, 416, 416, 3), intArrayOf(1, 84, 3549)).shouldBeNull()
        ModelLayout.parse(intArrayOf(416, 416, 3), intArrayOf(1, 84, 3549)).shouldBeNull()
    }

    "rejects outputs without enough class channels (e.g. NMS-free [1,300,6])" {
        ModelLayout.parse(intArrayOf(1, 416, 416, 3), intArrayOf(1, 300, 6)).shouldBeNull()
        ModelLayout.parse(intArrayOf(1, 416, 416, 3), intArrayOf(1, 84)).shouldBeNull()
    }
})
