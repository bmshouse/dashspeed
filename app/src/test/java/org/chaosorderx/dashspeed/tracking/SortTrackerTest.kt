package org.chaosorderx.dashspeed.tracking

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.chaosorderx.dashspeed.detection.Detection

private fun rect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) = RectF().apply {
    this.left = left
    this.top = top
    this.right = right
    this.bottom = bottom
}

// Feeds frames with monotonically increasing 33 ms timestamps, like a 30 fps camera.
private class FrameFeeder(private val tracker: SortTracker) {
    private var timestampMs = 0L

    fun feed(detections: List<Detection>): List<TrackedVehicle> {
        timestampMs += 33L
        return tracker.update(detections, timestampMs)
    }
}

class SortTrackerTest : FreeSpec({

    "returns empty list when no detections" {
        val tracker = SortTracker()
        FrameFeeder(tracker).feed(emptyList()).shouldBeEmpty()
    }

    "suppresses tracks until minHits frames" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        repeat(2) { feeder.feed(det) }
        feeder.feed(det).shouldBeEmpty() // not yet minHits=3
        feeder.feed(det).shouldHaveSize(1) // now visible
    }

    "removes stale tracks after maxAge frames" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        repeat(4) { feeder.feed(det) } // establish track
        repeat(tracker.maxAge + 1) { feeder.feed(emptyList()) } // starve it
        feeder.feed(emptyList()).shouldBeEmpty()
    }

    "assigns consistent IDs across frames" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        repeat(4) { feeder.feed(det) }
        val id1 = feeder.feed(det).first().id
        val id2 = feeder.feed(det).first().id
        id1 shouldBe id2
    }

    "history accumulates one sample per matched frame (first frame only creates the track)" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        // Frame 1 creates the track (no match yet, so no history sample). Frames 2-5 match
        // and each append a sample. minHits=3 means the track becomes visible at frame 4.
        repeat(4) { feeder.feed(det) }
        val vehicle = feeder.feed(det).first()
        vehicle.history shouldHaveSize 4
    }

    "history samples carry the frame timestamps they were fed with" {
        val tracker = SortTracker()
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        var result: List<TrackedVehicle> = emptyList()
        // Frame at t=100 only creates the track; matched frames from t=133 onward append samples.
        for (t in listOf(100L, 133L, 166L, 199L, 232L)) {
            result = tracker.update(det, t)
        }
        result.first().history.map { it.timestampMs } shouldBe listOf(133L, 166L, 199L, 232L)
    }

    "history is capped at 30 samples" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        repeat(40) { feeder.feed(det) }
        feeder.feed(det).first().history shouldHaveSize 30
    }

    "a weak detection cannot start a track" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val weak = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.2f))
        repeat(10) { feeder.feed(weak).shouldBeEmpty() }
    }

    "weak detections keep an established track alive through low-confidence frames" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val strong = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.8f))
        val weak = listOf(Detection(rect(101f, 100f, 201f, 200f), classId = 2, confidence = 0.2f))
        repeat(5) { feeder.feed(strong) }
        val id = feeder.feed(strong).first().id
        repeat(8) {
            val visible = feeder.feed(weak)
            visible shouldHaveSize 1
            visible.first().id shouldBe id
            visible.first().coasting shouldBe false
        }
    }

    "a confirmed track coasts for a couple of missed frames, then disappears" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        repeat(6) { feeder.feed(det) }
        val first = feeder.feed(emptyList())
        first shouldHaveSize 1
        first.first().coasting shouldBe true
        feeder.feed(emptyList()) shouldHaveSize 1 // second missed frame: still shown
        feeder.feed(emptyList()).shouldBeEmpty() // third: dropped from the output
    }

    "a coasting track resumes under the same id" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        repeat(6) { feeder.feed(det) }
        val id = feeder.feed(det).first().id
        feeder.feed(emptyList())
        feeder.feed(det).first().id shouldBe id
    }

    "a bottom-clipped detection updates the track but adds no speed sample" {
        val tracker = SortTracker()
        val feeder = FrameFeeder(tracker)
        val det = listOf(Detection(rect(100f, 100f, 200f, 200f), classId = 2, confidence = 0.9f))
        val clippedBox = rect(100f, 100f, 200f, 200f)
        val clipped = listOf(Detection(clippedBox, classId = 2, confidence = 0.9f, bottomClipped = true))
        repeat(5) { feeder.feed(det) }
        val before = feeder.feed(det).first().history.size
        val after = feeder.feed(clipped).first().history.size
        after shouldBe before
    }
})
