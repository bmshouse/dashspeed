package org.chaosorderx.dashspeed.speed

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// The calibrated (JSON-parsing) path of HomographyCalibrator uses org.json.JSONObject, which
// is not mocked under plain-JVM unit tests (see HomographyCalibratorTest.kt) — it needs a real
// Android runtime to exercise org.json for real.
@RunWith(AndroidJUnit4::class)
class HomographyCalibratorInstrumentedTest {
    private val calibrator = HomographyCalibrator()

    @Test
    fun calibratedMppGrowsTowardTheHorizon() {
        val json = calibrator.calibrate(mountHeightCm = 120f, tiltAngleDeg = 10f)
        val near = calibrator.metersPerPixel(350f, json)
        val far = calibrator.metersPerPixel(250f, json)
        assertTrue("expected far mpp ($far) > near mpp ($near)", far > near)
    }

    @Test
    fun groundDistanceMetersDecreasesAsYIncreases() {
        val json = calibrator.calibrate(mountHeightCm = 120f, tiltAngleDeg = 10f)
        val far = calibrator.groundDistanceMeters(250f, json)
        val near = calibrator.groundDistanceMeters(350f, json)
        assertTrue("expected near ($near) < far ($far)", near < far)
    }

    @Test
    fun groundDistanceMetersStaysFiniteAtAndAboveTheHorizon() {
        val json = calibrator.calibrate(mountHeightCm = 120f, tiltAngleDeg = 10f)
        val distance = calibrator.groundDistanceMeters(0f, json)
        assertTrue("expected finite positive distance, was $distance", distance in 0f..300f)
    }

    @Test
    fun groundDistanceMetersDifferencingAgreesWithMppLinearizationForSmallDy() {
        val json = calibrator.calibrate(mountHeightCm = 120f, tiltAngleDeg = 10f)
        val y = 300f
        val dy = 1f
        val exact = calibrator.groundDistanceMeters(y, json) - calibrator.groundDistanceMeters(y + dy, json)
        val linear = calibrator.metersPerPixel(y, json) * dy
        assertTrue("expected exact ($exact) within 5% of linear ($linear)", Math.abs(exact - linear) <= Math.abs(linear) * 0.05f)
    }

    @Test
    fun metersPerPixelReturnsDefaultForInvalidJson() {
        val mpp = calibrator.metersPerPixel(200f, "not json")
        assertTrue(mpp == HomographyCalibrator.DEFAULT_MPP)
    }
}
