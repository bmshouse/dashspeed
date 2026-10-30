package org.chaosorderx.dashspeed.camera

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class FrameRateGateTest : FreeSpec({
    "the very first frame is always accepted, whatever the clock value" {
        FrameRateGate().accept(0L) shouldBe true
        FrameRateGate().accept(Long.MAX_VALUE / 2) shouldBe true
        FrameRateGate().accept(-5L) shouldBe true
    }

    "a 30 fps camera passes every second frame" {
        val gate = FrameRateGate()
        val accepted = (0 until 30).map { gate.accept(it * 33L) }
        accepted.count { it } shouldBe 15
        accepted.take(4) shouldBe listOf(true, false, true, false)
    }

    "a 15 fps or slower camera passes everything" {
        val gate = FrameRateGate()
        (0 until 20).all { gate.accept(it * 67L) } shouldBe true
        val slow = FrameRateGate()
        (0 until 20).all { slow.accept(it * 200L) } shouldBe true
    }

    "timestamp jitter does not drop an on-schedule frame" {
        val gate = FrameRateGate()
        gate.accept(1000L) shouldBe true
        gate.accept(1060L) shouldBe true // 60 ms gap: slightly early, still within the jitter allowance
        gate.accept(1100L) shouldBe false
    }

    "a clock that jumps backwards recovers instead of stalling" {
        val gate = FrameRateGate()
        gate.accept(100_000L) shouldBe true
        gate.accept(5L) shouldBe true
        gate.accept(100L) shouldBe true
    }
})
