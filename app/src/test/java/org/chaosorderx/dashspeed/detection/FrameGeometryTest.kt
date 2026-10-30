package org.chaosorderx.dashspeed.detection

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class FrameGeometryTest : FreeSpec({
    val tol = 0.01f

    "letterbox" - {
        "pads top and bottom for a 4:3 landscape frame" {
            val lb = FrameGeometry.letterbox(640, 480, 416, 416)
            lb.scale shouldBe (0.65f plusOrMinus tol)
            lb.padX shouldBe (0f plusOrMinus tol)
            lb.padY shouldBe (52f plusOrMinus tol)
        }

        "maps padded model edges to the frame-normalised extremes" {
            val lb = FrameGeometry.letterbox(640, 480, 416, 416)
            lb.toNormY(52f) shouldBe (0f plusOrMinus tol)
            lb.toNormY(364f) shouldBe (416f plusOrMinus tol)
            lb.toNormY(208f) shouldBe (208f plusOrMinus tol)
            lb.toNormX(416f) shouldBe (416f plusOrMinus tol)
        }

        "clamps coordinates that fall inside the padding" {
            val lb = FrameGeometry.letterbox(640, 480, 416, 416)
            lb.toNormY(0f) shouldBe 0f
            lb.toNormY(416f) shouldBe 416f
        }
    }

    "letterbox with a rectangular model input" - {
        "4:3 frame in a 4:3 model needs no padding and keeps coordinates 1:1" {
            val lb = FrameGeometry.letterbox(640, 480, 640, 480)
            lb.scale shouldBe (1f plusOrMinus tol)
            lb.padX shouldBe (0f plusOrMinus tol)
            lb.padY shouldBe (0f plusOrMinus tol)
            lb.toNormX(320f) shouldBe (208f plusOrMinus tol)
            lb.toNormY(240f) shouldBe (208f plusOrMinus tol)
        }

        "4:3 frame in a smaller 4:3 model scales without padding" {
            val lb = FrameGeometry.letterbox(640, 480, 512, 384)
            lb.scale shouldBe (0.8f plusOrMinus tol)
            lb.padX shouldBe (0f plusOrMinus tol)
            lb.padY shouldBe (0f plusOrMinus tol)
            lb.toNormX(512f) shouldBe (416f plusOrMinus tol)
        }

        "4:3 frame in a square 640 model pads top and bottom" {
            val lb = FrameGeometry.letterbox(640, 480, 640, 640)
            lb.scale shouldBe (1f plusOrMinus tol)
            lb.padY shouldBe (80f plusOrMinus tol)
            lb.toNormY(80f) shouldBe (0f plusOrMinus tol)
            lb.toNormY(560f) shouldBe (416f plusOrMinus tol)
        }

        "a 16:9 frame in a 4:3 model pads top and bottom only" {
            val lb = FrameGeometry.letterbox(1280, 720, 640, 480)
            lb.scale shouldBe (0.5f plusOrMinus tol)
            lb.padX shouldBe (0f plusOrMinus tol)
            lb.padY shouldBe (60f plusOrMinus tol)
        }
    }

    "fillCenter" - {
        "crops the sides of a 4:3 frame on a wider view" {
            val fill = FrameGeometry.fillCenter(2000f, 900f, 4f / 3f)
            fill.dispW shouldBe (2000f plusOrMinus tol)
            fill.dispH shouldBe (1500f plusOrMinus tol)
            fill.offY shouldBe (-300f plusOrMinus tol)
            fill.toViewY(208f) shouldBe (450f plusOrMinus tol)
        }

        "crops top and bottom on a narrower view" {
            val fill = FrameGeometry.fillCenter(800f, 800f, 4f / 3f)
            fill.dispH shouldBe (800f plusOrMinus tol)
            fill.dispW shouldBe (1066.67f plusOrMinus 0.1f)
            fill.toViewX(208f) shouldBe (400f plusOrMinus tol)
        }
    }

    "cell lookup" - {
        val fill = FrameGeometry.fillCenter(800f, 800f, 4f / 3f)

        "maps view points to mask cells" {
            fill.colAt(400f) shouldBe StaticMask.COLS / 2
            fill.rowAt(0f) shouldBe 0
            fill.rowAt(799f) shouldBe StaticMask.ROWS - 1
        }

        "returns -1 outside the frame" {
            val square = FrameGeometry.fillCenter(1000f, 1000f, 1f)
            square.colAt(-1f) shouldBe -1
            square.rowAt(1000f) shouldBe -1
        }
    }

    "band crop" - {
        "a 224x640 model on a 640x480 frame sees a 224-row strip centred where asked" {
            val crop = FrameGeometry.bandCrop(640, 480, 640, 224, centerFrac = 0.5f)
            crop shouldBe FrameCrop(0, 128, 640, 224)
        }

        "the strip is kept inside the frame" {
            FrameGeometry.bandCrop(640, 480, 640, 224, centerFrac = 0.05f).top shouldBe 0
            FrameGeometry.bandCrop(640, 480, 640, 224, centerFrac = 0.99f).top shouldBe 256
        }

        "a 336x960 model on a 960x720 frame sees a 336-row strip" {
            FrameGeometry.bandCrop(960, 720, 960, 336, centerFrac = 0.5f) shouldBe FrameCrop(0, 192, 960, 336)
        }

        "models whose aspect needs the whole frame get the whole frame" {
            FrameGeometry.bandCrop(640, 480, 640, 480, 0.5f) shouldBe FrameCrop(0, 0, 640, 480)
            FrameGeometry.bandCrop(640, 480, 416, 416, 0.5f) shouldBe FrameCrop(0, 0, 640, 480)
        }

        "band models are recognised by aspect ratio" {
            FrameGeometry.isBandModel(640, 224) shouldBe true
            FrameGeometry.isBandModel(960, 336) shouldBe true
            FrameGeometry.isBandModel(640, 480) shouldBe false
            FrameGeometry.isBandModel(512, 384) shouldBe false
            FrameGeometry.isBandModel(416, 416) shouldBe false
        }

        "model coordinates map back through the strip into full-frame normalised space" {
            val crop = FrameGeometry.bandCrop(640, 480, 640, 224, centerFrac = 0.5f) // rows 128..352
            val lb = FrameGeometry.letterbox(640, 480, 640, 224, crop)
            lb.scale shouldBe (1f plusOrMinus tol)
            lb.isCropped shouldBe true
            // Model row 0 is frame row 128 -> 128/480 of the way down; model row 224 is frame row 352.
            lb.toNormY(0f) shouldBe (128f / 480f * 416f plusOrMinus tol)
            lb.toNormY(224f) shouldBe (352f / 480f * 416f plusOrMinus tol)
            lb.toNormX(320f) shouldBe (208f plusOrMinus tol)
            lb.imageBottom shouldBe (224f plusOrMinus tol)
        }

        "a strip shown in a taller model is padded and still maps back exactly" {
            val crop = FrameCrop(0, 100, 640, 200)
            val lb = FrameGeometry.letterbox(640, 480, 640, 320, crop) // 60 px of padding above and below
            lb.padY shouldBe (60f plusOrMinus tol)
            lb.toNormY(60f) shouldBe (100f / 480f * 416f plusOrMinus tol)
            lb.toNormY(260f) shouldBe (300f / 480f * 416f plusOrMinus tol)
            lb.imageBottom shouldBe (260f plusOrMinus tol)
        }

        "a strip shown at 1.5x scale maps back exactly" {
            val crop = FrameGeometry.bandCrop(640, 480, 960, 336, centerFrac = 0.5f) // 224-row strip at 1.5x
            val lb = FrameGeometry.letterbox(640, 480, 960, 336, crop)
            lb.scale shouldBe (1.5f plusOrMinus tol)
            lb.toNormY(336f) shouldBe ((crop.top + 224f) / 480f * 416f plusOrMinus tol)
        }
    }
})
