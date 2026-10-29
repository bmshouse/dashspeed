package org.chaosorderx.dashspeed.detection

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class DetectionFilterTest : FreeSpec({
    val emptyMask = StaticMask()

    // Right-hand strip (mount): cols 26..31 on every row.
    val mountMask =
        StaticMask(
            BooleanArray(StaticMask.COLS * StaticMask.ROWS) { it % StaticMask.COLS >= 26 },
        )

    "drops the observed full-width hood box even with an empty mask" {
        // Mean hood box from the field log: [0, 286, 415, 410].
        DetectionFilter.isStatic(0f, 286f, 415f, 410f, emptyMask) shouldBe true
    }

    "drops a box just over the width limit and keeps one just under" {
        val limit = ModelConfig.MAX_BOX_WIDTH_FRACTION * ModelConfig.FRAME_SPACE
        DetectionFilter.isStatic(0f, 200f, limit, 300f, emptyMask) shouldBe true
        DetectionFilter.isStatic(0f, 200f, limit - 1f, 300f, emptyMask) shouldBe false
    }

    "keeps ordinary vehicle boxes" {
        DetectionFilter.isStatic(173f, 203f, 239f, 260f, emptyMask) shouldBe false
        DetectionFilter.isStatic(59f, 202f, 133f, 258f, mountMask) shouldBe false
    }

    "still drops boxes mostly on the static mask" {
        // x 340..410 lies entirely in cols 26..31 (x >= 338).
        DetectionFilter.isStatic(340f, 100f, 410f, 200f, mountMask) shouldBe true
    }
})
