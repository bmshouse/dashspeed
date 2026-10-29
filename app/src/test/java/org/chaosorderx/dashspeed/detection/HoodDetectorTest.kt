package org.chaosorderx.dashspeed.detection

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun box(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) = Detection(
    boundingBox =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        },
    classId = 2,
    confidence = 0.8f,
)

// Field-log hood box: ~277 px wide, resting on the bottom edge (frame is 416).
private fun hood(jitter: Float = 0f) = box(126f + jitter, 307f, 403f + jitter, 415f)

private fun clippedBox(
    left: Float,
    right: Float,
    bottom: Float = 365f,
) = Detection(
    boundingBox =
        RectF().apply {
            this.left = left
            top = 250f
            this.right = right
            this.bottom = bottom
        },
    classId = 2,
    confidence = 0.3f,
    bottomClipped = true,
)

private fun car() = box(150f, 230f, 190f, 270f)

/** Feeds the same detections once per 100 ms for [seconds]; returns all zones flagged and the last kept list. */
private fun HoodDetector.run(
    seconds: Int,
    moving: Boolean = true,
    startMs: Long = 0L,
    frame: (Int) -> List<Detection>,
): Pair<List<HoodDetector.Zone>, List<Detection>> {
    val flagged = mutableListOf<HoodDetector.Zone>()
    var kept = emptyList<Detection>()
    repeat(seconds * 10) { i ->
        val (k, zones) = filter(frame(i), moving, startMs + i * 100L)
        kept = k
        flagged += zones
    }
    return flagged to kept
}

class HoodDetectorTest : FreeSpec({
    "flags a hood box that sits on the bottom edge while driving, then drops it" {
        val detector = HoodDetector()
        val (flagged, _) = detector.run(seconds = 4) { listOf(hood(jitter = (it % 3).toFloat()), car()) }
        flagged shouldHaveSize 1
        val (_, kept) = detector.run(seconds = 1, startMs = 10_000L) { listOf(hood(), car()) }
        kept shouldHaveSize 1
        kept.single().boundingBox.bottom shouldBe 270f
    }

    "keeps the hood box until it has persisted long enough" {
        val detector = HoodDetector()
        val (flagged, kept) = detector.run(seconds = 2) { listOf(hood()) }
        flagged.shouldBeEmpty()
        kept shouldHaveSize 1
    }

    "never flags a box that does not touch the bottom edge, however long it persists" {
        val detector = HoodDetector()
        val (flagged, kept) = detector.run(seconds = 10) { listOf(car()) }
        flagged.shouldBeEmpty()
        kept shouldHaveSize 1
    }

    "does not accumulate while stopped" {
        val detector = HoodDetector()
        val (flagged, _) = detector.run(seconds = 10, moving = false) { listOf(hood()) }
        flagged.shouldBeEmpty()
    }

    "forgets a candidate that disappears" {
        val detector = HoodDetector()
        detector.run(seconds = 2) { listOf(hood()) }
        detector.run(seconds = 3, startMs = 5_000L) { emptyList() }
        val (flagged, _) = detector.run(seconds = 2, startMs = 10_000L) { listOf(hood()) }
        flagged.shouldBeEmpty()
    }

    "a wide box clipped by the strip's bottom edge is flagged by its edge row, then dropped" {
        val detector = HoodDetector()
        // Hood seen through a band strip: box shape varies, but it is always cut at row 365.
        val clipped = { i: Int -> clippedBox(left = (i % 4) * 30f, right = 280f + (i % 3) * 40f) }
        detector.run(seconds = 2) { listOf(clipped(it)) }.second shouldHaveSize 1
        val (_, kept) = detector.run(seconds = 3, startMs = 2_000L) { listOf(clipped(it), car()) }
        kept shouldHaveSize 1
        kept.single().boundingBox.bottom shouldBe 270f
    }

    "a narrow clipped box or one at a different row is not treated as hood" {
        val detector = HoodDetector()
        detector.run(seconds = 5) { listOf(clippedBox(left = 100f, right = 140f)) }.second shouldHaveSize 1
        detector.run(seconds = 5, startMs = 6_000L) { listOf(clippedBox(left = 0f, right = 300f)) }
        // The wide box at row 365 is now an edge; a wide box clipped at row 300 is not.
        detector.run(seconds = 1, startMs = 20_000L) { listOf(clippedBox(left = 0f, right = 300f, bottom = 300f)) }
            .second shouldHaveSize 1
    }

    "clipped boxes do not accumulate while stopped" {
        val detector = HoodDetector()
        detector.run(seconds = 6, moving = false) { listOf(clippedBox(left = 0f, right = 300f)) }
        detector.run(seconds = 1, startMs = 10_000L) { listOf(clippedBox(left = 0f, right = 300f)) }
            .second shouldHaveSize 1
    }

    "reset forgets learned zones" {
        val detector = HoodDetector()
        detector.run(seconds = 4) { listOf(hood()) }
        detector.zones shouldHaveSize 1
        detector.reset()
        detector.zones.shouldBeEmpty()
        detector.run(seconds = 1, startMs = 20_000L) { listOf(hood()) }.second shouldHaveSize 1
    }
})
