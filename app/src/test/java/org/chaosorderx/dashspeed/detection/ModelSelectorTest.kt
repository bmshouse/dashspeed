package org.chaosorderx.dashspeed.detection

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ModelSelectorTest : FreeSpec({
    fun bench(
        file: String,
        scale: Float,
        p90: Float,
        band: Boolean = false,
    ) = ModelBenchmark(file, 640, 480, "gpu", medianMs = p90 * 0.8f, p90Ms = p90, scale = scale, band = band)

    val small = bench("small", scale = 0.65f, p90 = 30f)
    val medium = bench("medium", scale = 0.8f, p90 = 42f)
    val large = bench("large", scale = 1.0f, p90 = 70f)

    "picks the most detailed model within the latency budget" {
        ModelSelector.choose(listOf(small, medium, large), budgetP90Ms = 50f) shouldBe medium
    }

    "picks the most detailed model when the device is fast enough for all" {
        ModelSelector.choose(listOf(small, medium, large), budgetP90Ms = 100f) shouldBe large
    }

    "falls back to the fastest model when none meets the budget" {
        ModelSelector.choose(listOf(medium, large, small), budgetP90Ms = 10f) shouldBe small
    }

    "model exactly at the budget is acceptable" {
        ModelSelector.choose(listOf(small, medium), budgetP90Ms = 42f) shouldBe medium
    }

    "ties in detail go to the faster model, so a band model beats a full-frame one of equal resolution" {
        val full = bench("full", scale = 1.0f, p90 = 40f)
        val band = bench("band", scale = 1.0f, p90 = 18f, band = true)
        ModelSelector.choose(listOf(full, band), budgetP90Ms = 50f) shouldBe band
    }

    "a more detailed band model wins when it is affordable" {
        val full = bench("full", scale = 1.0f, p90 = 20f)
        val hiResBand = bench("hires", scale = 1.5f, p90 = 35f, band = true)
        ModelSelector.choose(listOf(full, hiResBand), budgetP90Ms = 50f) shouldBe hiResBand
        ModelSelector.choose(listOf(full, hiResBand), budgetP90Ms = 30f) shouldBe full
    }

    "no candidates means no choice" {
        ModelSelector.choose(emptyList()).shouldBeNull()
    }

    "a single model is chosen even if over budget" {
        ModelSelector.choose(listOf(large), budgetP90Ms = 10f) shouldBe large
    }

    "governor ladder is best-first and never includes models too weak for far vehicles" {
        val ladder = ModelSelector.governorLadder(listOf(small, large, medium), start = large)
        ladder.map { it.file } shouldBe listOf("large", "medium")
    }

    "governor ladder keeps the starting model even if it is itself below the floor" {
        val ladder = ModelSelector.governorLadder(listOf(small), start = small)
        ladder.map { it.file } shouldBe listOf("small")
    }
})
