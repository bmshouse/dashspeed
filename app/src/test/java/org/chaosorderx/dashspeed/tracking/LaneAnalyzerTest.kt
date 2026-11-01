package org.chaosorderx.dashspeed.tracking

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.pow

private const val FRAME_MS = 67L
private const val VP = LaneAnalyzer.DEFAULT_CENTER_X

/** A car whose centre sits [lateralM] metres from the road centre column, [width] px wide. */
private fun carAt(
    id: Int,
    width: Float,
    lateralM: Float,
    vp: Float = VP,
): TrackedVehicle {
    val cx = vp + lateralM * width / LaneMath.CAR_WIDTH_M
    return TrackedVehicle(
        id = id,
        boundingBox =
            RectF().apply {
                left = cx - width / 2f
                right = cx + width / 2f
                top = 200f
                bottom = 200f + width * 0.8f
            },
        history = emptyList(),
    )
}

/** Drives [seconds] of 15 fps frames; [cars] gives the tracks at time t (seconds). Returns the last report. */
private fun LaneAnalyzer.drive(
    seconds: Float,
    startMs: Long = 0L,
    learning: Boolean = true,
    cars: (Float) -> List<TrackedVehicle>,
): LaneReport {
    var report: LaneReport? = null
    val frames = (seconds * 1000 / FRAME_MS).toInt()
    repeat(frames) { i ->
        report = update(cars(i * FRAME_MS / 1000f), startMs + i * FRAME_MS, learning)
    }
    return report!!
}

private fun LaneReport.lane(lane: Int) = lanes.first { it.lane == lane }

class LaneAnalyzerTest : FreeSpec({
    "laneOf" - {
        "maps lateral offsets to own, left and right lanes" {
            LaneMath.laneOf(0.5f, null) shouldBe 0
            LaneMath.laneOf(-2.5f, null) shouldBe -1
            LaneMath.laneOf(3.5f, null) shouldBe 1
        }

        "keeps the previous lane until the car is clearly in another" {
            LaneMath.laneOf(2.3f, previous = 0) shouldBe 0
            LaneMath.laneOf(2.8f, previous = 0) shouldBe 1
        }
    }

    "an own-lane car growing at 0.25/s gives a sustained approach with 4 s to contact" {
        val report = LaneAnalyzer().drive(seconds = 4f) { t -> listOf(carAt(1, 20f * exp(0.25f * t), 0.5f)) }
        val approach = report.approach.shouldNotBeNull()
        approach.growthPerSec shouldBe (0.25f plusOrMinus 0.01f)
        approach.ttcSec shouldBe (4f plusOrMinus 0.2f)
        val expectedClosing = approach.growthPerSec * LeadVehicleSelector.distanceMeters(54f)
        approach.closingMps shouldBe (expectedClosing plusOrMinus 0.6f)
        (approach.sustainedMs >= 1_000L) shouldBe true
        report.lane(0).cars shouldBe 1
        report.lane(0).closingMps.shouldNotBeNull() shouldBe (approach.closingMps plusOrMinus 3f)
    }

    "a car holding its size is not an approach and has ~zero closing speed" {
        val report = LaneAnalyzer().drive(seconds = 5f) { listOf(carAt(1, 30f, 0.5f)) }
        report.approach.shouldBeNull()
        report.lane(0).closingMps.shouldNotBeNull() shouldBe (0f plusOrMinus 0.2f)
    }

    "the approach ends when the car stops closing" {
        val analyzer = LaneAnalyzer()
        analyzer.drive(seconds = 4f) { t -> listOf(carAt(1, 20f * exp(0.25f * t), 0.5f)) }
        val report = analyzer.drive(seconds = 3f, startMs = 4_000L) { listOf(carAt(1, 54f, 0.5f)) }
        report.approach.shouldBeNull()
    }

    "a receding car in the left lane has negative closing speed" {
        val report = LaneAnalyzer().drive(seconds = 5f) { t -> listOf(carAt(2, 40f * exp(-0.1f * t), -2.5f)) }
        report.lane(-1).cars shouldBe 1
        report.lane(-1).closingMps.shouldNotBeNull() shouldBe (-1.9f plusOrMinus 0.6f) // g −0.1/s × ~19 m
        report.lane(0).cars shouldBe 0
        report.lane(0).closingMps.shouldBeNull()
    }

    "a box clipped by the frame edge is not used" {
        val clipped =
            TrackedVehicle(
                id = 3,
                boundingBox =
                    RectF().apply {
                        left = 0f
                        right = 40f
                        top = 250f
                        bottom = 290f
                    },
                history = emptyList(),
            )
        val report = LaneAnalyzer().drive(seconds = 3f) { listOf(clipped) }
        report.lanes.all { it.cars == 0 } shouldBe true
    }

    "a track that vanishes is forgotten" {
        val analyzer = LaneAnalyzer()
        analyzer.drive(seconds = 3f) { listOf(carAt(1, 30f, 0.5f)) }
        val report = analyzer.drive(seconds = 1f, startMs = 3_000L) { emptyList() }
        report.lane(0).cars shouldBe 0
    }

    "learns the road centre column from cars that grow or shrink" {
        val analyzer = LaneAnalyzer()
        val laterals = listOf(-5.5f, -2.5f, 0.5f, 0.2f, 0.8f, -2.2f, -2.8f, 3.5f, -5.2f, 0.4f)
        analyzer.drive(seconds = 15f) { t ->
            val width = 15f * 2f.pow(t / 15f)
            laterals.mapIndexed { i, lateral -> carAt(i + 1, width, lateral, vp = 190f) }
        }
        analyzer.roadCenterX shouldBe (190f plusOrMinus 2f)
    }

    "the default centre is where the field logs put it" {
        LaneAnalyzer.DEFAULT_CENTER_X shouldBe (192f plusOrMinus 1f)
    }

    "uses the default centre until enough evidence has been seen" {
        LaneAnalyzer().roadCenterX shouldBe LaneAnalyzer.DEFAULT_CENTER_X
    }

    "reports each track's lane once it has been seen a few frames" {
        val analyzer = LaneAnalyzer()
        val cars = { listOf(carAt(1, 30f, 0.5f), carAt(2, 30f, -2.5f), carAt(3, 30f, 3.5f)) }
        analyzer.update(cars(), 0L).laneByTrack shouldBe emptyMap()
        val report = analyzer.drive(seconds = 0.5f, startMs = FRAME_MS) { cars() }
        report.laneByTrack shouldBe mapOf(1 to 0, 2 to -1, 3 to 1)
    }

    "a persisted road centre is restored, unless out of range or already learned this session" {
        LaneAnalyzer().apply { load(190f) }.roadCenterX shouldBe 190f
        LaneAnalyzer().apply { load(0f) }.roadCenterX shouldBe LaneAnalyzer.DEFAULT_CENTER_X
        LaneAnalyzer().apply { load(400f) }.roadCenterX shouldBe LaneAnalyzer.DEFAULT_CENTER_X
        val learned = LaneAnalyzer()
        val laterals = listOf(-5.5f, -2.5f, 0.5f, 0.2f, 0.8f, -2.2f, -2.8f, 3.5f, -5.2f, 0.4f)
        learned.drive(seconds = 15f) { t ->
            laterals.mapIndexed { i, lateral -> carAt(i + 1, 15f * 2f.pow(t / 15f), lateral, vp = 190f) }
        }
        learned.load(230f)
        learned.roadCenterX shouldBe (190f plusOrMinus 2f)
    }

    "emits the learned road centre for persistence and reset forgets it" {
        val analyzer = LaneAnalyzer()
        val emitted = mutableListOf<Float>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { analyzer.learnedFlow.collect { emitted += it } }
        val laterals = listOf(-5.5f, -2.5f, 0.5f, 0.2f, 0.8f, -2.2f, -2.8f, 3.5f, -5.2f, 0.4f)
        analyzer.drive(seconds = 15f) { t ->
            laterals.mapIndexed { i, lateral -> carAt(i + 1, 15f * 2f.pow(t / 15f), lateral, vp = 190f) }
        }
        job.cancel()
        emitted.last() shouldBe (190f plusOrMinus 2f)
        analyzer.reset()
        analyzer.roadCenterX shouldBe LaneAnalyzer.DEFAULT_CENTER_X
    }
    "road centre from own-lane cars" - {
        val trueCenter = 196f // 4 px right of the default: a lane-0 car at 10 px wide is still read as lane 0

        "a settled, distant own-lane car pulls the centre to where it implies" {
            val analyzer = LaneAnalyzer()
            analyzer.drive(seconds = 25f) { listOf(carAt(1, 10f, 0.5f, vp = trueCenter)) }
            analyzer.roadCenterX shouldBe (trueCenter plusOrMinus 1f)
        }

        "cars in the neighbouring lanes do not vote" {
            val analyzer = LaneAnalyzer()
            analyzer.drive(seconds = 25f) {
                listOf(carAt(1, 10f, -2.5f, vp = trueCenter), carAt(2, 10f, 3.5f, vp = trueCenter))
            }
            analyzer.roadCenterX shouldBe LaneAnalyzer.DEFAULT_CENTER_X
        }

        "a car too big or too small for a precise reading does not vote" {
            val analyzer = LaneAnalyzer()
            analyzer.drive(seconds = 25f) {
                listOf(carAt(1, 30f, 0.5f, vp = trueCenter), carAt(2, 4f, 0.5f, vp = trueCenter))
            }
            analyzer.roadCenterX shouldBe LaneAnalyzer.DEFAULT_CENTER_X
        }

        "nothing is learned while learning is off (turning, crawling)" {
            val analyzer = LaneAnalyzer()
            analyzer.drive(seconds = 25f, learning = false) { listOf(carAt(1, 10f, 0.5f, vp = trueCenter)) }
            analyzer.roadCenterX shouldBe LaneAnalyzer.DEFAULT_CENTER_X
        }

        "a vote far from the current estimate is ignored (cannot be pulled by a wrongly placed car)" {
            val analyzer = LaneAnalyzer()
            // Reads as lane 0 only if the analyzer's centre is near; at 30 px away it is lane +/-N, not a voter.
            analyzer.drive(seconds = 25f) { listOf(carAt(1, 10f, 0.5f, vp = LaneAnalyzer.DEFAULT_CENTER_X + 30f)) }
            analyzer.roadCenterX shouldBe LaneAnalyzer.DEFAULT_CENTER_X
        }
    }

    "the learned centre never leaves the allowed range" {
        val analyzer = LaneAnalyzer()
        val laterals = listOf(-5.5f, -2.5f, 0.5f, 0.2f, 0.8f, -2.2f, -2.8f, 3.5f, -5.2f, 0.4f)
        analyzer.drive(seconds = 15f) { t ->
            laterals.mapIndexed { i, lateral -> carAt(i + 1, 15f * 2f.pow(t / 15f), lateral, vp = 230f) }
        }
        analyzer.roadCenterX shouldBe LaneAnalyzer.CENTER_MAX
    }
})
