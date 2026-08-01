package org.chaosorderx.dashspeed.speed

import android.graphics.PointF
import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import org.chaosorderx.dashspeed.tracking.PositionSample
import org.chaosorderx.dashspeed.tracking.TrackedVehicle

private fun pt(
    x: Float,
    y: Float,
) = PointF().apply {
    this.x = x
    this.y = y
}

private fun linearTrack(
    id: Int,
    relativeMps: Float,
    outlierAt: Int = -1,
    outlierPx: Float = 0f,
): TrackedVehicle {
    val dtMs = 33L
    val frames = 10
    val pxPerFrame = relativeMps * (dtMs / 1_000f) / HomographyCalibrator.DEFAULT_MPP
    val history =
        (0 until frames).map { k ->
            val jitter = if (k == outlierAt) outlierPx else 0f
            PositionSample(
                bottomCenter = pt(150f, 200f - pxPerFrame * k + jitter),
                timestampMs = 1_000L + dtMs * k,
            )
        }
    val box =
        RectF().apply {
            top = 100f
            bottom = 300f
        }
    return TrackedVehicle(id = id, boundingBox = box, history = history)
}

class SpeedEstimatorPropertyTest : FreeSpec({

    var estimator = SpeedEstimator(HomographyCalibrator())

    beforeTest { estimator = SpeedEstimator(HomographyCalibrator()) }

    "noise-free linear motion recovers the true absolute speed" {
        var id = 0
        checkAll(
            Arb.float(5f, 35f),
            Arb.float(-20f, 20f),
        ) { egoMps, relativeMps ->
            val result = estimator.estimate(linearTrack(++id, relativeMps), egoMps)
            val absolute = egoMps + relativeMps
            if (absolute in 0f..67f) {
                result.shouldNotBeNull()
                result.speedMph / 2.237f shouldBe (absolute plusOrMinus 0.5f)
            }
        }
    }

    "all non-null results are within the valid display range" {
        var id = 0
        checkAll(
            Arb.float(0f, 35f),
            Arb.float(-60f, 60f),
        ) { egoMps, relativeMps ->
            val result = estimator.estimate(linearTrack(++id, relativeMps), egoMps)
            if (result != null) {
                result.speedMph shouldBeGreaterThanOrEqualTo 0f
                result.speedMph shouldBeLessThanOrEqualTo 67f * 2.237f
            }
        }
    }

    "a single corrupted sample never shifts the estimate by more than 1 m/s" {
        var id = 0
        checkAll(
            Arb.float(-10f, 10f),
            Arb.int(0, 9),
            Arb.float(-50f, 50f),
        ) { relativeMps, outlierAt, outlierPx ->
            val egoMps = 26.8f
            val result = estimator.estimate(linearTrack(++id, relativeMps, outlierAt, outlierPx), egoMps)
            result.shouldNotBeNull()
            result.speedMph / 2.237f shouldBe (egoMps + relativeMps plusOrMinus 1f)
        }
    }
})
