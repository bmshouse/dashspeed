package org.chaosorderx.dashspeed.tracking

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe

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

class KalmanBoxTrackerTest : FreeSpec({

    "predict returns initial box when no updates received" {
        val box = rect(100f, 100f, 200f, 200f)
        val tracker = KalmanBoxTracker(box, id = 1)
        val predicted = tracker.predict()
        predicted.left.shouldBeWithinPercentageOf(box.left, 5.0)
        predicted.top.shouldBeWithinPercentageOf(box.top, 5.0)
    }

    "update then predict moves state toward measured box" {
        val initial = rect(100f, 100f, 200f, 200f)
        val tracker = KalmanBoxTracker(initial, id = 1)
        val updated = rect(110f, 110f, 210f, 210f)
        tracker.update(updated, frameTimestampMs = 33L)
        val predicted = tracker.predict()
        predicted.left.shouldBeWithinPercentageOf(updated.left, 20.0)
    }

    "timeSinceUpdate increments each predict without update" {
        val tracker = KalmanBoxTracker(rect(0f, 0f, 50f, 50f), id = 2)
        tracker.predict()
        tracker.predict()
        tracker.timeSinceUpdate shouldBe 2
    }

    "hitStreak is not reset by first predict after a match (timeSinceUpdate was 0)" {
        val tracker = KalmanBoxTracker(rect(100f, 100f, 200f, 200f), id = 3)
        tracker.update(rect(100f, 100f, 200f, 200f), frameTimestampMs = 33L) // hitStreak=1, timeSinceUpdate=0
        tracker.update(rect(100f, 100f, 200f, 200f), frameTimestampMs = 33L) // hitStreak=2, timeSinceUpdate=0
        tracker.predict() // timeSinceUpdate was 0 → no reset; now timeSinceUpdate=1
        tracker.hitStreak shouldBe 2
    }

    "hitStreak resets to 0 on second consecutive miss" {
        val tracker = KalmanBoxTracker(rect(100f, 100f, 200f, 200f), id = 4)
        tracker.update(rect(100f, 100f, 200f, 200f), frameTimestampMs = 33L) // hitStreak=1, timeSinceUpdate=0
        tracker.predict() // miss 1: timeSinceUpdate was 0, no reset; timeSinceUpdate=1
        tracker.predict() // miss 2: timeSinceUpdate was 1 > 0, reset hitStreak=0
        tracker.hitStreak shouldBe 0
    }
})
