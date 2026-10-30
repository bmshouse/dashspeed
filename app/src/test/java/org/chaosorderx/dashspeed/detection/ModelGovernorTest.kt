package org.chaosorderx.dashspeed.detection

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ModelGovernorTest : FreeSpec({
    fun bench(
        file: String,
        pixels: Int,
        p90: Float,
    ) = ModelBenchmark(file, width = pixels / 100, height = 100, backend = "gpu", medianMs = p90 * 0.8f, p90Ms = p90)

    // Best first: large 40 ms, medium 32 ms, small 28 ms (cool-phone benchmarks).
    val ladder = listOf(bench("large", 64000, 40f), bench("medium", 51200, 32f), bench("small", 41600, 28f))

    /** Feeds one frame every 66 ms for [seconds], starting at [startMs]; returns the first recommendation. */
    fun feed(
        governor: ModelGovernor,
        frameMs: Float,
        startMs: Long,
        seconds: Int,
    ): Pair<Int?, Long> {
        var t = startMs
        val end = startMs + seconds * 1000L
        while (t < end) {
            governor.record(frameMs, t)?.let { return it to t }
            t += 66
        }
        return null to t
    }

    "stays put while frames are fast" {
        val governor = ModelGovernor(ladder, startIndex = 0)
        feed(governor, 35f, 0, 120).first.shouldBeNull()
    }

    "steps down one rung when frames stay slow, but not before the dwell time" {
        val governor = ModelGovernor(ladder, startIndex = 0)
        val (target, at) = feed(governor, 90f, 0, 60)
        target shouldBe 1
        (at >= ModelGovernor.DOWN_DWELL_MS) shouldBe true
    }

    "ignores a short slow spell" {
        val governor = ModelGovernor(ladder, startIndex = 0)
        feed(governor, 35f, 0, 20)
        feed(governor, 120f, 20_000, 2).first.shouldBeNull()
    }

    "does not go below the smallest model" {
        val governor = ModelGovernor(ladder, startIndex = 2)
        feed(governor, 150f, 0, 60).first.shouldBeNull()
    }

    "climbs back up after a long calm spell if the estimate fits" {
        val governor = ModelGovernor(ladder, startIndex = 0)
        governor.onSwitched(1, nowMs = 0)
        // medium runs at 25 ms; large would be about 25 * 40/32 = 31 ms, within the 50 ms budget.
        val (target, at) = feed(governor, 25f, 0, 200)
        target shouldBe 0
        (at >= ModelGovernor.BASE_UP_DWELL_MS) shouldBe true
    }

    "does not climb when the larger model would not fit" {
        val governor = ModelGovernor(ladder, startIndex = 0)
        governor.onSwitched(1, nowMs = 0)
        // medium at 45 ms -> large estimated 56 ms > 50 ms budget; and 45 is under the 62 ms down limit.
        feed(governor, 45f, 0, 300).first.shouldBeNull()
    }

    "never climbs above the benchmark's original choice" {
        val governor = ModelGovernor(ladder, startIndex = 1)
        feed(governor, 10f, 0, 400).first.shouldBeNull()
    }

    "a failed climb doubles the wait before the next one" {
        val governor = ModelGovernor(ladder, startIndex = 0)
        governor.onSwitched(1, nowMs = 0)
        governor.onSwitched(0, nowMs = 100_000) // climbed
        governor.onSwitched(1, nowMs = 130_000) // stepped back down within the failed-upgrade window
        val (target, at) = feed(governor, 25f, 130_000, 400)
        target shouldBe 0
        (at - 130_000 >= 2 * ModelGovernor.BASE_UP_DWELL_MS) shouldBe true
    }
})
