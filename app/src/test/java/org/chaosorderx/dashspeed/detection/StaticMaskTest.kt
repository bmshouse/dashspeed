package org.chaosorderx.dashspeed.detection

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class StaticMaskTest : FreeSpec({
    fun bottomBand(rowsFromBottom: Int): StaticMask {
        val cells = BooleanArray(StaticMask.COLS * StaticMask.ROWS)
        for (row in StaticMask.ROWS - rowsFromBottom until StaticMask.ROWS) {
            for (col in 0 until StaticMask.COLS) cells[row * StaticMask.COLS + col] = true
        }
        return StaticMask(cells)
    }

    "withRegion marks the cells whose centre is inside the box, leaving the original untouched" {
        val original = StaticMask()
        val merged = original.withRegion(126f, 307f, 403f, 415f)
        merged.coverage(150f, 330f, 380f, 400f) shouldBe (1f plusOrMinus 0.001f)
        merged.coverage(150f, 100f, 380f, 200f) shouldBe 0f
        original.coveredFraction shouldBe 0f
    }

    "coverage" - {
        // Bottom 6 of 24 rows = y in 312..416.
        val mask = bottomBand(6)

        "is 1 for a box fully on static cells" {
            mask.coverage(100f, 330f, 300f, 410f) shouldBe (1f plusOrMinus 0.001f)
        }

        "is 0 for a box above the band" {
            mask.coverage(100f, 100f, 300f, 200f) shouldBe 0f
        }

        "is fractional for a box straddling the band" {
            // 100px tall box, 50px (312..362) inside the band -> 0.5
            mask.coverage(100f, 262f, 300f, 362f) shouldBe (0.5f plusOrMinus 0.001f)
        }

        "is 0 for a degenerate box" {
            mask.coverage(10f, 10f, 10f, 20f) shouldBe 0f
        }
    }

    "serialisation round-trips and rejects bad input" {
        val mask = bottomBand(3)
        StaticMask.deserialize(mask.serialize())!!.serialize() shouldBe mask.serialize()
        StaticMask.deserialize("").shouldBeNull()
        StaticMask.deserialize("101").shouldBeNull()
    }
})
