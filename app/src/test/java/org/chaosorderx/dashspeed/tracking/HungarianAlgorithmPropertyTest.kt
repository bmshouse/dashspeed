package org.chaosorderx.dashspeed.tracking

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

class HungarianAlgorithmPropertyTest : FreeSpec({

    "every track is assigned at most one detection" {
        checkAll(
            Arb.int(1, 6),
            Arb.int(1, 6),
            Arb.list(Arb.float(0f, 1f), 36..36),
        ) { tracks, detections, costValues ->
            val cost = Array(tracks) { i -> FloatArray(detections) { j -> costValues[i * detections + j] } }
            val assignment = HungarianAlgorithm().solve(cost)
            val validAssignments = assignment.filter { it >= 0 }
            validAssignments.size shouldBe validAssignments.distinct().size // no duplicates
        }
    }
})
