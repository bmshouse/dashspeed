package org.chaosorderx.dashspeed.gps

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class GpsManagerTest : FreeSpec({

    "bearingDelta returns difference for same-hemisphere bearings" {
        bearingDelta(10f, 30f) shouldBe (20f plusOrMinus 0.01f)
    }

    "bearingDelta handles wraparound at the 360/0 boundary" {
        // 350° to 10° is 20°, not 340°
        bearingDelta(350f, 10f) shouldBe (20f plusOrMinus 0.01f)
    }

    "bearingDelta returns 0 for identical bearings" {
        bearingDelta(180f, 180f) shouldBe (0f plusOrMinus 0.01f)
    }

    "bearingDelta is symmetric" {
        bearingDelta(30f, 10f) shouldBe bearingDelta(10f, 30f)
    }

    "bearingDelta caps at 180 degrees" {
        bearingDelta(0f, 180f) shouldBe (180f plusOrMinus 0.01f)
    }
})
