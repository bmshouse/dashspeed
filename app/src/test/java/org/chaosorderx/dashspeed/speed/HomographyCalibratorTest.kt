package org.chaosorderx.dashspeed.speed

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

// Only the uncalibrated (empty matrixJson) fallback path is exercised here. The calibrated
// path parses org.json.JSONObject, which is not mocked under this project's plain-JVM unit
// tests (see JVM unit-test notes in CLAUDE.md); it's covered instead by
// androidTest/.../speed/HomographyCalibratorInstrumentedTest.kt.
class HomographyCalibratorTest : FreeSpec({

    lateinit var calibrator: HomographyCalibrator

    beforeTest { calibrator = HomographyCalibrator() }

    "metersPerPixel returns DEFAULT_MPP without calibration" {
        calibrator.metersPerPixel(200f, "") shouldBe HomographyCalibrator.DEFAULT_MPP
    }

    "groundDistanceMeters uncalibrated fallback is affine with slope DEFAULT_MPP" {
        val d1 = calibrator.groundDistanceMeters(200f, "")
        val d2 = calibrator.groundDistanceMeters(210f, "")
        (d1 - d2) shouldBe (10f * HomographyCalibrator.DEFAULT_MPP plusOrMinus 1e-4f)
    }

    "groundDistanceMeters uncalibrated fallback decreases as y increases" {
        val far = calibrator.groundDistanceMeters(100f, "")
        val near = calibrator.groundDistanceMeters(300f, "")
        (far - near) shouldBe (200f * HomographyCalibrator.DEFAULT_MPP plusOrMinus 1e-4f)
    }
})
