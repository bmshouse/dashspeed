package org.chaosorderx.dashspeed.tracking

import android.graphics.RectF
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.chaosorderx.dashspeed.overlay.SpeedAnnotation

private fun track(
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

/** Every given track in our own lane. */
private fun own(vararg tracks: TrackedVehicle): Map<Int, Int> = tracks.associate { it.id to 0 }

class LeadVehicleSelectorTest : FreeSpec({
    // Lanes come from LaneAnalyzer; here they are given directly. The horizon is at row 200.

    "picks the widest (nearest) car in our own lane, ignoring other lanes" {
        val ahead = track(1, 195f, 190f, 221f, 230f) // 26 px wide
        val nearerButLeft = track(2, 100f, 190f, 150f, 260f) // 50 px wide, in the left lane
        val farther = track(3, 205f, 195f, 213f, 208f)
        val lanes = mapOf(1 to 0, 2 to -1, 3 to 0)
        val lead = LeadVehicleSelector.select(listOf(ahead, nearerButLeft, farther), emptyMap(), null, lanes)
        lead.shouldNotBeNull().trackId shouldBe 1
    }

    "a car in another lane is never the lead, even when it is alone" {
        val left = track(1, 100f, 190f, 150f, 260f)
        LeadVehicleSelector.select(listOf(left), emptyMap(), null, mapOf(1 to -1)).shouldBeNull()
        val right = track(2, 300f, 190f, 350f, 260f)
        LeadVehicleSelector.select(listOf(right), emptyMap(), null, mapOf(2 to 1)).shouldBeNull()
    }

    "a track whose lane is not yet known is ignored" {
        val fresh = track(1, 195f, 190f, 221f, 230f)
        LeadVehicleSelector.select(listOf(fresh), emptyMap(), null, emptyMap()).shouldBeNull()
    }

    "ignores a wide track resting on the bottom edge (the car's own hood)" {
        val hood = track(1, 126f, 307f, 403f, 415f)
        val car = track(2, 190f, 230f, 226f, 270f)
        LeadVehicleSelector.select(listOf(hood), emptyMap(), null, own(hood)).shouldBeNull()
        val lead = LeadVehicleSelector.select(listOf(hood, car), emptyMap(), 1, own(hood, car))
        lead.shouldNotBeNull().trackId shouldBe 2
    }

    "a small, far vehicle is still the lead (no speed needed)" {
        val tiny = track(7, 202f, 196f, 213f, 208f) // 11 px wide, 12 px tall
        val lead = LeadVehicleSelector.select(listOf(tiny), emptyMap(), null, own(tiny)).shouldNotBeNull()
        lead.speedMph.shouldBeNull()
        lead.distanceMeters shouldBe (48.6f plusOrMinus 0.5f)
    }

    "carries speed and approximate flag from the annotation" {
        val t = track(4, 190f, 190f, 226f, 240f)
        val annotation =
            SpeedAnnotation(trackId = 4, modelBox = RectF(), speedMph = 61.8f, relativeMph = -2f, approximate = true)
        val lead = LeadVehicleSelector.select(listOf(t), mapOf(4 to annotation), null, own(t)).shouldNotBeNull()
        lead.speedMph shouldBe 61.8f
        lead.speedIsApproximate shouldBe true
    }

    "ignores objects above the horizon and slivers" {
        val aboveHorizon = track(1, 200f, 150f, 216f, 190f)
        val sliver = track(2, 200f, 205f, 216f, 210f)
        LeadVehicleSelector.select(listOf(aboveHorizon), emptyMap(), null, own(aboveHorizon)).shouldBeNull()
        LeadVehicleSelector.select(listOf(sliver), emptyMap(), null, own(sliver)).shouldBeNull()
    }

    "sticks with the current lead unless a challenger is clearly wider" {
        val current = track(1, 195f, 190f, 221f, 230f) // 26 px
        val slightlyWider = track(2, 192f, 192f, 224f, 236f) // 32 px: ratio 1.23, below the switch ratio
        LeadVehicleSelector.select(listOf(current, slightlyWider), emptyMap(), 1, own(current, slightlyWider))
            .shouldNotBeNull().trackId shouldBe 1
        val muchWider = track(3, 185f, 200f, 231f, 262f) // 46 px
        LeadVehicleSelector.select(listOf(current, muchWider), emptyMap(), 1, own(current, muchWider))
            .shouldNotBeNull().trackId shouldBe 3
    }

    "no vehicles means no lead" {
        LeadVehicleSelector.select(emptyList(), emptyMap(), null, emptyMap()).shouldBeNull()
    }

    "distance from width is inversely proportional" {
        val near = LeadVehicleSelector.distanceMeters(20f)
        val far = LeadVehicleSelector.distanceMeters(10f)
        (near * 2f) shouldBe (far plusOrMinus 0.01f)
    }
})
