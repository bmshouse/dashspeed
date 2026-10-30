package org.chaosorderx.dashspeed.detection

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

private const val BAND_H = 224f / 480f // a 224-row strip of a 480-row frame

private fun detection(
    topRow480: Float,
    bottomRow480: Float,
    confidence: Float = 0.8f,
    clipped: Boolean = false,
) = Detection(
    boundingBox =
        RectF().apply {
            left = 100f
            top = topRow480 / 480f * ModelConfig.FRAME_SPACE
            right = 130f
            bottom = bottomRow480 / 480f * ModelConfig.FRAME_SPACE
        },
    classId = 2,
    confidence = confidence,
    bottomClipped = clipped,
)

/** Feeds [count] frames (15 fps), each with the given detections, starting at [startMs]; returns the end time. */
private fun BandLearner.drive(
    detections: List<Detection>,
    seconds: Int,
    startMs: Long = 0L,
    bandHeight: Float? = BAND_H,
): Long {
    var t = startMs
    repeat(seconds * 15) {
        observe(detections, moving = true, bandHeightFrac = bandHeight, nowMs = t)
        t += 67
    }
    return t
}

class BandLearnerTest : FreeSpec({
    val tol = 0.02f
    // Vehicles from row 200 (far) to row 300 (near) in a 480-row frame, like the field logs.
    val road = listOf(detection(200f, 215f), detection(230f, 260f), detection(260f, 300f))

    "starts at the default centre and is not yet learned" {
        val learner = BandLearner()
        learner.centerFrac shouldBe (DEFAULT_BAND_CENTER_FRAC plusOrMinus 0.0001f)
        learner.learned shouldBe false
    }

    "ignores boxes resting on the bottom edge (the hood)" {
        val withHood = BandLearner().apply { drive(road + detection(300f, 480f), seconds = 260) }
        val roadOnly = BandLearner().apply { drive(road, seconds = 260) }
        withHood.learned shouldBe true
        withHood.centerFrac shouldBe (roadOnly.centerFrac plusOrMinus 0.0001f)
    }

    "does not move before enough evidence has accumulated" {
        val learner = BandLearner()
        learner.drive(road, seconds = 60)
        learner.learned shouldBe false
    }

    "learns the centre of the observed span once there is enough driving" {
        val learner = BandLearner()
        learner.drive(road, seconds = 180)
        learner.learned shouldBe true
        // span 200..300 plus margins (26 above, 24 below) -> centre ~ (174 + 324) / 2 = 249 of 480
        learner.centerFrac shouldBe (249f / 480f plusOrMinus tol)
    }

    "ignores low-confidence and bottom-clipped boxes" {
        val learner = BandLearner()
        val noise = listOf(detection(50f, 90f, confidence = 0.2f), detection(380f, 470f, clipped = true))
        learner.drive(road + noise, seconds = 180)
        learner.centerFrac shouldBe (249f / 480f plusOrMinus tol)
    }

    "when the strip is too short for the span it keeps the near vehicles (bottom) covered" {
        val learner = BandLearner()
        val wide = listOf(detection(120f, 200f), detection(300f, 380f))
        learner.drive(wide, seconds = 180, bandHeight = 100f / 480f)
        // bottom p99 ~380 + 24 margin = 404; centre = 404 - 50 = 354 of 480
        learner.centerFrac shouldBe (354f / 480f plusOrMinus tol)
    }

    "centre is quantised to 16-row steps and kept inside the frame" {
        val learner = BandLearner()
        learner.drive(listOf(detection(440f, 475f)), seconds = 180)
        val steps = learner.centerFrac / BandLearner.QUANTUM
        steps shouldBe (steps.toInt().toFloat() plusOrMinus 0.01f)
        (learner.centerFrac <= 1f - BAND_H / 2f + 0.001f) shouldBe true
    }

    "does nothing while a full-frame model is active (no band height)" {
        val learner = BandLearner()
        learner.drive(road, seconds = 180, bandHeight = null)
        learner.learned shouldBe false
    }

    "a small drift does not move an already learned centre" {
        val learner = BandLearner()
        var t = learner.drive(road, seconds = 180)
        val before = learner.centerFrac
        val drifted = listOf(detection(205f, 220f), detection(235f, 262f), detection(262f, 305f))
        t = learner.drive(drifted, seconds = 120, startMs = t)
        learner.centerFrac shouldBe before
    }

    "evidence outside the strip moves it immediately" {
        val learner = BandLearner()
        val t = learner.drive(road, seconds = 180)
        val before = learner.centerFrac
        val high = listOf(detection(100f, 140f))
        repeat(3) { learner.observeOutside(high, BAND_H, t + it * 1_000L) }
        (learner.centerFrac < before) shouldBe true
    }

    "load restores a saved centre, ignores unset values; reset returns to default" {
        val learner = BandLearner()
        learner.load(0f)
        learner.learned shouldBe false
        learner.load(0.6f)
        learner.centerFrac shouldBe (0.6f plusOrMinus 0.0001f)
        learner.learned shouldBe true
        learner.reset()
        learner.centerFrac shouldBe (DEFAULT_BAND_CENTER_FRAC plusOrMinus 0.0001f)
        learner.learned shouldBe false
    }

    "observations while stopped are not counted" {
        val learner = BandLearner()
        var t = 0L
        repeat(180 * 15) {
            learner.observe(road, moving = false, bandHeightFrac = BAND_H, nowMs = t)
            t += 67
        }
        learner.learned shouldBe false
    }
})
