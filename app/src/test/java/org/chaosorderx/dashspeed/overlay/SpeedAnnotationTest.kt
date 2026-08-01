package org.chaosorderx.dashspeed.overlay

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.chaosorderx.dashspeed.ui.theme.DashSpeedColors

private fun annotation(relativeMph: Float) =
    SpeedAnnotation(
        trackId = 1,
        modelBox = RectF(),
        speedMph = 60f,
        relativeMph = relativeMph,
    )

class SpeedAnnotationTest : FreeSpec({

    "closing vehicle (relativeMph < -5) is BadgeRed" {
        annotation(-10f).badgeColor() shouldBe DashSpeedColors.BadgeRed
    }

    "pulling-away vehicle (relativeMph > 5) is BadgeGreen" {
        annotation(10f).badgeColor() shouldBe DashSpeedColors.BadgeGreen
    }

    "near-speed vehicle (|relativeMph| <= 5) is BadgeAmber" {
        annotation(0f).badgeColor() shouldBe DashSpeedColors.BadgeAmber
        annotation(5f).badgeColor() shouldBe DashSpeedColors.BadgeAmber
        annotation(-5f).badgeColor() shouldBe DashSpeedColors.BadgeAmber
    }
})
