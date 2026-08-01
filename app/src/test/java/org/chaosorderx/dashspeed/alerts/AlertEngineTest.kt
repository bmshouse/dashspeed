package org.chaosorderx.dashspeed.alerts

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.chaosorderx.dashspeed.overlay.SpeedAnnotation

// shouldAlert() is a top-level internal function — no Context or TTS needed here.
// Full TTS-playback tests live in androidTest/ (Phase 7).
class AlertEngineTest : FreeSpec({

    fun alert(
        annotations: List<SpeedAnnotation>,
        now: Long = 10_000L,
        lastAlertMs: Long = 0L,
        cooldownMs: Long = 8_000L,
        thresholdMph: Float = 15f,
    ) = shouldAlert(annotations, now, lastAlertMs, cooldownMs, thresholdMph)

    "fires when closing vehicle exceeds threshold and cooldown has elapsed" {
        alert(listOf(annotation(-20f))) shouldBe "Fast approach"
    }

    "returns null when closing speed is below threshold" {
        alert(listOf(annotation(-10f)), thresholdMph = 15f).shouldBeNull()
    }

    "returns null when cooldown has not elapsed" {
        alert(listOf(annotation(-20f)), now = 5_000L, lastAlertMs = 0L, cooldownMs = 8_000L).shouldBeNull()
    }

    "returns null for vehicle pulling away" {
        alert(listOf(annotation(20f))).shouldBeNull()
    }

    "returns null for empty annotation list" {
        alert(emptyList()).shouldBeNull()
    }
})

private fun annotation(relativeMph: Float) =
    SpeedAnnotation(
        trackId = 1,
        modelBox = RectF(),
        speedMph = 60f,
        relativeMph = relativeMph,
    )
