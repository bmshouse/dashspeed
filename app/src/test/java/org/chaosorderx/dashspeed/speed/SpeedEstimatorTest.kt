package org.chaosorderx.dashspeed.speed

import android.graphics.PointF
import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.chaosorderx.dashspeed.tracking.PositionSample
import org.chaosorderx.dashspeed.tracking.TrackedVehicle

class SpeedEstimatorTest : FreeSpec({

    lateinit var estimator: SpeedEstimator

    beforeTest { estimator = SpeedEstimator(HomographyCalibrator()) }

    "stationary vehicle reads near-zero absolute speed" {
        val egoMps = 26.8f
        val track = buildLinearTrack(id = 1, relativeMps = -egoMps)
        val result = estimator.estimate(track, egoSpeedMps = egoMps)
        result.shouldNotBeNull()
        result.speedMph shouldBe (0f plusOrMinus 0.5f)
    }

    "vehicle 10 mph faster reads approximately ego + 10 mph" {
        val egoMps = 26.8f
        val track = buildLinearTrack(id = 2, relativeMps = 4.47f)
        val result = estimator.estimate(track, egoSpeedMps = egoMps)
        result.shouldNotBeNull()
        result.speedMph.shouldBeWithinPercentageOf(70f, 8.0)
    }

    "one wild jitter sample does not skew the estimate (oblique-box regression)" {
        val egoMps = 26.8f
        val track = buildLinearTrack(id = 3, relativeMps = 4.47f, frames = 10, outlierAt = 5, outlierPx = 30f)
        val result = estimator.estimate(track, egoSpeedMps = egoMps)
        result.shouldNotBeNull()
        result.speedMph.shouldBeWithinPercentageOf(70f, 8.0)
    }

    "returns null for implausible negative absolute speed" {
        val track = buildLinearTrack(id = 4, relativeMps = -5f)
        estimator.estimate(track, egoSpeedMps = 0f).shouldBeNull()
    }

    "returns null for speed above 150 mph" {
        val track = buildLinearTrack(id = 5, relativeMps = 40f)
        estimator.estimate(track, egoSpeedMps = 40f).shouldBeNull()
    }

    "returns null for relative speed beyond the symmetric gate (oncoming traffic)" {
        val track = buildLinearTrack(id = 6, relativeMps = -50f)
        estimator.estimate(track, egoSpeedMps = 30f).shouldBeNull()
    }

    "stopped car ahead at highway ego speed is still reported" {
        val egoMps = 29f // 65 mph
        val track = buildLinearTrack(id = 7, relativeMps = -egoMps)
        val result = estimator.estimate(track, egoSpeedMps = egoMps)
        result.shouldNotBeNull()
        result.speedMph shouldBe (0f plusOrMinus 0.5f)
    }

    "returns null when history has fewer than MIN_SAMPLES samples" {
        val track = buildLinearTrack(id = 8, relativeMps = 4.47f, frames = SpeedEstimator.MIN_SAMPLES - 1)
        estimator.estimate(track, egoSpeedMps = 26.8f).shouldBeNull()
    }

    "returns null when history spans less than the minimum window" {
        val track = buildLinearTrack(id = 9, relativeMps = 4.47f, frames = 6, dtMs = 10L) // 50 ms span
        estimator.estimate(track, egoSpeedMps = 26.8f).shouldBeNull()
    }

    "EMA resets on track ID change" {
        val track1 = buildLinearTrack(id = 10, relativeMps = 20f)
        val track2 = buildLinearTrack(id = 11, relativeMps = 35f)
        estimator.estimate(track1, egoSpeedMps = 0f)
        val result = estimator.estimate(track2, egoSpeedMps = 0f)
        result.shouldNotBeNull()
        result.speedMph shouldBeGreaterThan 70f
    }

    "returns null for bounding box shorter than MIN_BOX_HEIGHT_PX" {
        val track = buildLinearTrack(id = 20, relativeMps = 20f, boxHeightPx = 10f)
        estimator.estimate(track, egoSpeedMps = 0f).shouldBeNull()
    }

    "cleanupTracks evicts stale EMA entries — evicted track resets to raw value" {
        val estimator2 = SpeedEstimator(HomographyCalibrator())
        val track1 = buildLinearTrack(id = 30, relativeMps = 20f)
        val track2 = buildLinearTrack(id = 31, relativeMps = 35f)
        // Seed EMA for both tracks
        estimator2.estimate(track1, egoSpeedMps = 0f)
        estimator2.estimate(track2, egoSpeedMps = 0f)
        // Evict track 30; only track 31 remains active
        estimator2.cleanupTracks(setOf(31))
        // Track 30 re-estimated: EMA has no prior state, so result == raw value
        val fresh = estimator2.estimate(buildLinearTrack(id = 30, relativeMps = 20f), egoSpeedMps = 0f)
        fresh.shouldNotBeNull()
        // After eviction, first estimate is raw (no smoothing), so speedMph ~ 20*2.237
        fresh.speedMph.shouldBeWithinPercentageOf(44.7f, 3.0)
    }
})

// PointF(float, float) constructor is a no-op in Android unit-test stubs — use field assignment.
private fun pt(
    x: Float,
    y: Float,
) = PointF().apply {
    this.x = x
    this.y = y
}

/**
 * A track whose box bottom moves linearly at [relativeMps] under the uncalibrated fallback
 * model (dy per second = −relativeMps / DEFAULT_MPP). Positive relative = pulling away = box
 * moves up. [outlierAt]/[outlierPx] optionally corrupt one sample to simulate box jitter.
 */
private fun buildLinearTrack(
    id: Int,
    relativeMps: Float,
    frames: Int = 6,
    dtMs: Long = 33L,
    boxHeightPx: Float = 200f,
    outlierAt: Int = -1,
    outlierPx: Float = 0f,
): TrackedVehicle {
    val baseTimeMs = 1_000L
    val pxPerFrame = relativeMps * (dtMs / 1_000f) / HomographyCalibrator.DEFAULT_MPP
    val history =
        (0 until frames).map { k ->
            val jitter = if (k == outlierAt) outlierPx else 0f
            PositionSample(
                bottomCenter = pt(150f, 200f - pxPerFrame * k + jitter),
                timestampMs = baseTimeMs + dtMs * k,
            )
        }
    val box =
        RectF().apply {
            top = 100f
            bottom = 100f + boxHeightPx
        }
    return TrackedVehicle(id = id, boundingBox = box, history = history)
}
