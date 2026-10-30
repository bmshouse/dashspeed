package org.chaosorderx.dashspeed.alerts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.chaosorderx.dashspeed.tracking.OwnLaneApproach

// shouldAlert() is a top-level internal function — no Context or TTS needed here.
// Full TTS-playback tests live in androidTest/ (Phase 7).
class AlertEngineTest : FreeSpec({

    // 20 mph ≈ 8.9 m/s closing, growth 0.25/s → time to contact 4 s.
    fun approach(
        closingMph: Float = 20f,
        sustainedMs: Long = 1_200L,
    ) = OwnLaneApproach(
        trackId = 1,
        growthPerSec = 0.25f,
        closingMps = closingMph / MPS_TO_MPH,
        sustainedMs = sustainedMs,
    )

    fun alert(
        approach: OwnLaneApproach?,
        now: Long = 10_000L,
        lastAlertMs: Long = 0L,
        cooldownMs: Long = 8_000L,
        thresholdMph: Float = 15f,
    ) = shouldAlert(approach, now, lastAlertMs, cooldownMs, thresholdMph)

    "fires when the own-lane car is closing fast, sustained, and the cooldown has elapsed" {
        alert(approach()) shouldBe "Fast approach"
    }

    "returns null when closing speed is below the threshold" {
        alert(approach(closingMph = 10f), thresholdMph = 15f).shouldBeNull()
    }

    "returns null until the approach has been sustained" {
        alert(approach(sustainedMs = 400L)).shouldBeNull()
    }

    "returns null when the cooldown has not elapsed" {
        alert(approach(), now = 5_000L, lastAlertMs = 0L, cooldownMs = 8_000L).shouldBeNull()
    }

    "returns null when there is no approaching car" {
        alert(null).shouldBeNull()
    }

    "time to contact is the reciprocal of the growth rate" {
        approach().ttcSec shouldBe 4f
    }
})
