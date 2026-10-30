package org.chaosorderx.dashspeed.detection

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

class StaticMaskLearnerTest : FreeSpec({
    val cols = StaticMask.COLS
    val rows = StaticMask.ROWS
    val hoodRows = 5
    val mountCols = 3

    fun isHood(
        col: Int,
        row: Int,
    ) = row >= rows - hoodRows || col < mountCols

    // Scenery cells get fresh random luma every frame; hood/mount cells stay constant.
    fun frame(rng: Random) =
        FloatArray(cols * rows) { i ->
            if (isHood(i % cols, i / cols)) 80f else rng.nextFloat() * 255f
        }

    fun drive(
        learner: StaticMaskLearner,
        seconds: Int,
    ) {
        val rng = Random(1)
        var now = 0L
        repeat(seconds * 1000 / StaticMaskLearner.COMPARE_INTERVAL_MS.toInt()) {
            learner.ingest(frame(rng), now)
            now += StaticMaskLearner.COMPARE_INTERVAL_MS
        }
    }

    "learns a bottom hood band and a left mount strip from constant cells" {
        val learner = StaticMaskLearner()
        drive(learner, 120)
        val mask = learner.mask
        mask.isStatic(cols / 2, rows - 1) shouldBe true
        mask.isStatic(1, 5) shouldBe true
        mask.isStatic(cols / 2, 5) shouldBe false
        mask.isStatic(cols - 1, 5) shouldBe false
        mask.coverage(0f, 400f, 416f, 416f) shouldBe (1f plusOrMinus 0.001f)
    }

    "mask learned from a full drive has exactly the hood and mount cells" {
        val learner = StaticMaskLearner()
        drive(learner, 120)
        learner.mask.serialize().count { it == '1' } shouldBe (cols * hoodRows + mountCols * (rows - hoodRows))
    }

    "buildMask ignores unchanging cells not connected to a bottom/side edge (e.g. sky)" {
        val worst = FloatArray(cols * rows) { 50f }
        for (col in 8 until 24) worst[col] = 0f // top row, away from side edges
        StaticMaskLearner.buildMask(worst).coveredFraction shouldBe 0f
    }

    "load restores a mask and reset clears it" {
        val learner = StaticMaskLearner()
        val saved = StaticMaskLearner.buildMask(FloatArray(cols * rows) { if (it / cols >= rows - 2) 0f else 50f })
        learner.load(saved.serialize())
        learner.mask.isStatic(0, rows - 1) shouldBe true
        learner.reset()
        learner.mask.coveredFraction shouldBe 0f
    }

    "ignores blank persisted state" {
        val learner = StaticMaskLearner()
        learner.load("")
        learner.mask.coveredFraction shouldBe 0f
    }

    "addRegion adds a hood zone that survives learning finishing, and reset clears it" {
        val learner = StaticMaskLearner()
        learner.addRegion(HoodDetector.Zone(126f, 307f, 403f, 415f))
        learner.mask.isStatic(10, rows - 1) shouldBe true
        learner.resetCount shouldBe 0
        learner.reset()
        learner.mask.coveredFraction shouldBe 0f
        learner.resetCount shouldBe 1
    }

    "setManual installs the mask, stops learning, and is emitted for persistence" {
        val learner = StaticMaskLearner()
        val painted = StaticMask(BooleanArray(cols * rows) { it % cols == 0 })
        var emitted: String? = null
        val job = CoroutineScope(Dispatchers.Unconfined).launch { learner.learned.collect { emitted = it } }
        learner.setManual(painted)
        job.cancel()
        learner.learningFlow.value shouldBe false
        learner.mask.isStatic(0, 3) shouldBe true
        emitted shouldBe painted.serialize()
    }
})
