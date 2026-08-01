package org.chaosorderx.dashspeed.tracking

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class HungarianAlgorithmTest : FreeSpec({

    "square matrix" - {
        "assigns optimally for 3x3 cost matrix" {
            val cost =
                arrayOf(
                    floatArrayOf(4f, 1f, 3f),
                    floatArrayOf(2f, 0f, 5f),
                    floatArrayOf(3f, 2f, 2f),
                )
            val result = HungarianAlgorithm().solve(cost)
            result shouldBe intArrayOf(1, 0, 2) // total cost = 5
        }
        "returns identity assignment for zero-cost diagonal" {
            val cost = Array(3) { i -> FloatArray(3) { j -> if (i == j) 0f else 1f } }
            HungarianAlgorithm().solve(cost) shouldBe intArrayOf(0, 1, 2)
        }
    }

    "non-square matrix" - {
        "handles more detections than tracks" {
            val cost = arrayOf(floatArrayOf(0.5f, 0.2f, 0.8f)) // 1 track, 3 detections
            val result = HungarianAlgorithm().solve(cost)
            result.size shouldBe 1
            result[0] shouldBe 1 // cheapest detection
        }
        "handles more tracks than detections" {
            val cost = arrayOf(floatArrayOf(0.1f), floatArrayOf(0.9f)) // 2 tracks, 1 detection
            val result = HungarianAlgorithm().solve(cost)
            result[0] shouldBe 0 // track 0 gets the detection
            result[1] shouldBe -1 // track 1 unassigned
        }
    }
})
