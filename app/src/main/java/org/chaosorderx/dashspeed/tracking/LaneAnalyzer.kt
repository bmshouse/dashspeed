package org.chaosorderx.dashspeed.tracking

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.chaosorderx.dashspeed.detection.ModelConfig
import kotlin.math.abs
import kotlin.math.ln

/** Traffic in one lane relative to ours: −1 = left, 0 = own, +1 = right. */
data class LaneSummary(
    val lane: Int,
    /** Cars currently qualifying as in this lane (tracked long enough, big enough, not clipped by the frame edge). */
    val cars: Int,
    /** Median closing speed of the nearest few cars, m/s; positive = we are catching up. Null when no car qualifies. */
    val closingMps: Float?,
)

/** The nearest own-lane car is closing fast; [sustainedMs] is how long it has been (the alert needs a second). */
data class OwnLaneApproach(
    val trackId: Int,
    val growthPerSec: Float,
    val closingMps: Float,
    val sustainedMs: Long,
) {
    /** Time to contact at the current closing rate. Independent of camera calibration and car size. */
    val ttcSec: Float get() = 1f / growthPerSec
}

data class LaneReport(
    val lanes: List<LaneSummary>,
    val approach: OwnLaneApproach?,
    val roadCenterX: Float,
    /** Lane (0 = own) of every current track that has been seen long enough to place. */
    val laneByTrack: Map<Int, Int>,
)

private class Sample(
    val tMs: Long,
    val width: Float,
    val centerX: Float,
)

private class TrackHistory {
    val samples = ArrayDeque<Sample>() // last few seconds, every frame
    val slow = ArrayDeque<Sample>() // last minute, every 0.5 s — for learning the road centre
    val lateral = ArrayDeque<Float>()
    var lane: Int? = null
    var lastVoteMs = LaneAnalyzer.NEVER

    fun add(sample: Sample) {
        samples.addLast(sample)
        while (sample.tMs - samples.first().tMs > LaneAnalyzer.RETAIN_MS) samples.removeFirst()
        if (slow.isEmpty() || sample.tMs - slow.last().tMs >= LaneAnalyzer.SLOW_STEP_MS) slow.addLast(sample)
        while (sample.tMs - slow.first().tMs > LaneAnalyzer.SLOW_RETAIN_MS) slow.removeFirst()
    }

    /** d ln(width) / dt over the last [windowMs] (per second), or null if there is too little data in it. */
    fun growth(
        nowMs: Long,
        windowMs: Long,
    ): Float? {
        val window = samples.filter { it.tMs >= nowMs - windowMs }
        if (window.size < LaneAnalyzer.MIN_GROWTH_POINTS) return null
        if (window.last().tMs - window.first().tMs < LaneAnalyzer.MIN_SPAN_FRACTION * windowMs) return null
        val t = window.map { (it.tMs - window.first().tMs) / MS_PER_SEC }
        val y = window.map { ln(it.width.toDouble()) }
        return LaneMath.fitLine(t, y)?.first?.toFloat()
    }

    private companion object {
        const val MS_PER_SEC = 1000.0
    }
}

private class Candidate(
    val trackId: Int,
    val history: TrackHistory,
    val width: Float,
    val lane: Int,
)

/**
 * Turns per-car tracks into per-lane traffic trends and an own-lane approach warning.
 *
 * - **Lane** of each track comes from its width-scaled lateral offset from the road's vanishing-point column
 *   (learned from how cars' centres move as they grow or shrink), smoothed over 9 frames with hysteresis.
 * - **Growth rate** g = d ln(width)/dt is the closing speed as a fraction of distance. It needs no calibration;
 *   multiplying by the rough distance from box width gives m/s. Per lane we take the median over the nearest
 *   [CLUSTER_CARS] cars, then a 3 s trailing median.
 * - **Approach**: the nearest own-lane car with g ≥ [APPROACH_GROWTH] (time-to-contact under 5 s).
 *
 * Call once per camera frame in order, with the frame capture timestamp. Not thread-safe.
 */
class LaneAnalyzer {
    private val histories = HashMap<Int, TrackHistory>()
    private val votes = ArrayDeque<Float>()
    private val smoothing = LANES.associateWith { ArrayDeque<Pair<Long, Float>>() }
    private var approachSinceMs: Long? = null
    private var lastEmittedX: Float? = null

    private val _learnedFlow =
        MutableSharedFlow<Float>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits the road centre each time it has moved enough to be worth persisting. */
    val learnedFlow: Flow<Float> = _learnedFlow.asSharedFlow()

    /** Column of the road's vanishing point in frame space; the default until enough evidence has been seen. */
    var roadCenterX: Float = ModelConfig.FRAME_SPACE / 2f
        private set

    fun update(
        tracks: List<TrackedVehicle>,
        timestampMs: Long,
    ): LaneReport {
        tracks.forEach { record(it, timestampMs) }
        val liveIds = tracks.map { it.id }.toSet()
        histories.keys.retainAll(liveIds)
        learnRoadCenter(timestampMs)
        val candidates = candidates(timestampMs)
        val lanes = LANES.map { summarise(it, candidates, timestampMs) }
        val laneByTrack =
            tracks.mapNotNull { t ->
                histories[t.id]?.takeIf { it.lateral.size >= MIN_LANE_SAMPLES }?.lane?.let { t.id to it }
            }.toMap()
        return LaneReport(lanes, ownLaneApproach(candidates, timestampMs), roadCenterX, laneByTrack)
    }

    /** Restores a persisted road centre; ignored once this session has learned its own, or if out of range. */
    fun load(centerX: Float) {
        if (centerX !in CENTER_MIN..CENTER_MAX || votes.size >= MIN_VOTES) return
        roadCenterX = centerX
        lastEmittedX = centerX
    }

    /** Forgets everything (e.g. after moving the phone). */
    fun reset() {
        histories.clear()
        votes.clear()
        smoothing.values.forEach { it.clear() }
        approachSinceMs = null
        roadCenterX = ModelConfig.FRAME_SPACE / 2f
        lastEmittedX = null
    }

    private fun record(
        track: TrackedVehicle,
        nowMs: Long,
    ) {
        val box = track.boundingBox
        val width = box.right - box.left
        if (width < 1f) return
        val history = histories.getOrPut(track.id) { TrackHistory() }
        // A box cut off by the frame edge has a misleading width and centre.
        val clipped = box.left <= EDGE_PX || box.right >= ModelConfig.FRAME_SPACE - EDGE_PX
        if (clipped) return
        val centerX = (box.left + box.right) / 2f
        history.add(Sample(nowMs, width, centerX))
        history.lateral.addLast(LaneMath.lateralMeters(centerX, width, roadCenterX))
        if (history.lateral.size > LATERAL_SMOOTHING) history.lateral.removeFirst()
        history.lane = LaneMath.laneOf(LaneMath.median(history.lateral.toList()), history.lane)
    }

    private fun candidates(nowMs: Long): List<Candidate> =
        histories.mapNotNull { (id, h) ->
            val last = h.samples.lastOrNull() ?: return@mapNotNull null
            val lane = h.lane?.takeIf { it in LANES } ?: return@mapNotNull null
            val seasoned = h.samples.size >= MIN_CLUSTER_SAMPLES && last.width >= MIN_WIDTH_PX
            if (seasoned && last.tMs >= nowMs - STALE_MS) Candidate(id, h, last.width, lane) else null
        }

    private fun summarise(
        lane: Int,
        candidates: List<Candidate>,
        nowMs: Long,
    ): LaneSummary {
        val inLane = candidates.filter { it.lane == lane }
        val closings =
            inLane.sortedByDescending { it.width }.take(CLUSTER_CARS).mapNotNull { c ->
                c.history.growth(nowMs, CLUSTER_WINDOW_MS)?.let { it * LeadVehicleSelector.distanceMeters(c.width) }
            }
        val recent = smoothing.getValue(lane)
        if (closings.isNotEmpty()) recent.addLast(nowMs to LaneMath.median(closings))
        while (recent.isNotEmpty() && nowMs - recent.first().first > SMOOTH_MS) recent.removeFirst()
        val smoothed = if (recent.isEmpty()) null else LaneMath.median(recent.map { it.second })
        return LaneSummary(lane, inLane.size, smoothed)
    }

    private fun ownLaneApproach(
        candidates: List<Candidate>,
        nowMs: Long,
    ): OwnLaneApproach? {
        val nearest = candidates.filter { it.lane == 0 }.maxByOrNull { it.width }
        val growth = nearest?.history?.growth(nowMs, APPROACH_WINDOW_MS)
        if (nearest == null || growth == null || growth < APPROACH_GROWTH) {
            approachSinceMs = null
            return null
        }
        val since = approachSinceMs ?: nowMs.also { approachSinceMs = it }
        val closing = growth * LeadVehicleSelector.distanceMeters(nearest.width)
        return OwnLaneApproach(nearest.trackId, growth, closing, nowMs - since)
    }

    /** Each track, once it has grown or shrunk enough, votes with where its centre column converges as width → 0. */
    private fun learnRoadCenter(nowMs: Long) {
        for (h in histories.values) {
            val vote = voteOf(h, nowMs) ?: continue
            votes.addLast(vote)
            if (votes.size > MAX_VOTES) votes.removeFirst()
        }
        if (votes.size >= MIN_VOTES) {
            roadCenterX = LaneMath.median(votes.toList()).coerceIn(CENTER_MIN, CENTER_MAX)
            val saved = lastEmittedX
            if (saved == null || abs(roadCenterX - saved) >= PERSIST_STEP_PX) {
                lastEmittedX = roadCenterX
                _learnedFlow.tryEmit(roadCenterX)
            }
        }
    }

    /** The column this track's centre converges to as its width shrinks, if it has changed size enough to tell. */
    private fun voteOf(
        h: TrackHistory,
        nowMs: Long,
    ): Float? {
        val ready = nowMs - h.lastVoteMs >= VOTE_GAP_MS && h.slow.size >= MIN_VOTE_POINTS
        val widths = h.slow.map { it.width }
        if (!ready || widths.max() / widths.min() < MIN_WIDTH_RATIO) return null
        h.lastVoteMs = nowMs
        val fit = LaneMath.fitLine(widths.map { it.toDouble() }, h.slow.map { it.centerX.toDouble() })
        return fit?.second?.takeIf { it in VOTE_RANGE }?.toFloat()
    }

    internal companion object {
        val LANES = listOf(-1, 0, 1)
        const val NEVER = Long.MIN_VALUE / 4
        const val EDGE_PX = 1f
        const val RETAIN_MS = 4_500L
        const val SLOW_STEP_MS = 500L
        const val SLOW_RETAIN_MS = 60_000L
        const val STALE_MS = 200L
        const val LATERAL_SMOOTHING = 9
        const val MIN_LANE_SAMPLES = 3
        const val PERSIST_STEP_PX = 2f
        const val MIN_CLUSTER_SAMPLES = 12
        const val MIN_WIDTH_PX = 8f
        const val CLUSTER_CARS = 3
        const val CLUSTER_WINDOW_MS = 3_000L
        const val SMOOTH_MS = 3_000L
        const val APPROACH_WINDOW_MS = 1_750L
        const val APPROACH_GROWTH = 0.2f
        const val MIN_GROWTH_POINTS = 8
        const val MIN_SPAN_FRACTION = 0.6f
        const val VOTE_GAP_MS = 4_000L
        const val MIN_VOTE_POINTS = 20
        const val MIN_WIDTH_RATIO = 1.5f
        const val MAX_VOTES = 60
        const val MIN_VOTES = 8
        const val CENTER_MIN = 150f
        const val CENTER_MAX = 260f
        val VOTE_RANGE = 100.0..320.0
    }
}
