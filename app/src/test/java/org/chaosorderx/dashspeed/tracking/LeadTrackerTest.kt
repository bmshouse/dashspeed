package org.chaosorderx.dashspeed.tracking

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun car(
    id: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) = TrackedVehicle(
    id = id,
    boundingBox =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        },
    history = emptyList(),
)

private const val FRAME_MS = 67L

private fun LeadTracker.step(
    tracks: List<TrackedVehicle>,
    t: Long,
    lanes: Map<Int, Int> = tracks.associate { it.id to 0 },
) = update(tracks, emptyMap(), t, lanes)

class LeadTrackerTest : FreeSpec({
    // `a` is the current lead; `b` is a clearly wider (nearer) car in the same lane.
    val a = car(1, 195f, 190f, 221f, 230f) // 26 px wide
    val b = car(2, 190f, 195f, 226f, 250f) // 36 px wide: beyond the single-frame switch ratio

    "a challenger must persist before it takes the highlight" {
        val tracker = LeadTracker()
        var t = 0L
        tracker.step(listOf(a), t).shouldNotBeNull().trackId shouldBe 1
        repeat(LeadTracker.SWITCH_FRAMES - 1) {
            t += FRAME_MS
            tracker.step(listOf(a, b), t).shouldNotBeNull().trackId shouldBe 1
        }
        t += FRAME_MS
        tracker.step(listOf(a, b), t).shouldNotBeNull().trackId shouldBe 2
    }

    "a challenger that comes and goes never takes over" {
        val tracker = LeadTracker()
        var t = 0L
        tracker.step(listOf(a), t)
        repeat(20) {
            t += FRAME_MS
            // Challenger visible on every other frame only: its run keeps restarting.
            val tracks = if (it % 2 == 0) listOf(a, b) else listOf(a)
            tracker.step(tracks, t).shouldNotBeNull().trackId shouldBe 1
        }
    }

    "switches at once when the old lead is gone" {
        val tracker = LeadTracker()
        tracker.step(listOf(a), 0L)
        tracker.step(listOf(b), FRAME_MS).shouldNotBeNull().trackId shouldBe 2
    }

    "switches at once when the old lead moves out of our lane" {
        val tracker = LeadTracker()
        tracker.step(listOf(a, b), 0L, lanes = mapOf(1 to 0, 2 to -1)).shouldNotBeNull().trackId shouldBe 1
        // `a` changes lanes while `b` (still tracked) is now in our lane: no waiting period.
        tracker.step(listOf(a, b), FRAME_MS, lanes = mapOf(1 to 1, 2 to 0)).shouldNotBeNull().trackId shouldBe 2
    }

    "a car in the next lane never becomes the lead" {
        val tracker = LeadTracker()
        repeat(30) { i ->
            val lead = tracker.step(listOf(a, b), i * FRAME_MS, lanes = mapOf(1 to 0, 2 to -1))
            lead.shouldNotBeNull().trackId shouldBe 1
        }
    }

    "holds the last lead briefly when nothing is found, then lets go" {
        val tracker = LeadTracker()
        tracker.step(listOf(a), 0L)
        tracker.step(emptyList(), 400L).shouldNotBeNull().trackId shouldBe 1
        tracker.step(emptyList(), LeadTracker.HOLD_MS + FRAME_MS).shouldBeNull()
    }

    "the hold does not extend itself" {
        val tracker = LeadTracker()
        tracker.step(listOf(a), 0L)
        var t = 0L
        repeat(20) {
            t += FRAME_MS
            tracker.step(emptyList(), t)
        }
        tracker.step(emptyList(), t + FRAME_MS).shouldBeNull()
    }
})
