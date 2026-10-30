package org.chaosorderx.dashspeed.detection

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Learns where in the frame vehicles appear, so a band model's strip can be placed on the road
 * instead of on sky and hood. Works in fractions of the frame height (0 = top, 1 = bottom), so it is
 * independent of camera resolution.
 *
 * While driving it keeps slowly decaying histograms of detected boxes' top and bottom edges. Once
 * enough evidence has accumulated, the strip is centred on the span from the 1st percentile of tops
 * to the 99th percentile of bottoms (plus margins); where the strip is shorter than that span, the
 * bottom (nearer vehicles) takes priority. Changes are rate-limited and quantised so the strip does
 * not jitter. Evidence of vehicles *outside* the strip (from the detector's periodic full-frame
 * probe) moves it at once.
 */
@Singleton
class BandLearner
    @Inject
    constructor() {
        /** Current strip centre (fraction of frame height). */
        @Volatile
        var centerFrac: Float = DEFAULT_BAND_CENTER_FRAC
            private set

        /** True once the centre comes from observed or restored data rather than the default. */
        @Volatile
        var learned: Boolean = false
            private set

        private val tops = DoubleArray(BINS)
        private val bottoms = DoubleArray(BINS)
        private var mass = 0.0
        private var movingMs = 0L
        private var lastObservedMs: Long? = null
        private var lastUpdateMs: Long? = null
        private val outsideEventsMs = ArrayDeque<Long>()

        private val _learnedFlow =
            MutableSharedFlow<Float>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Emits the new centre each time it changes, for persistence. */
        val learnedFlow: Flow<Float> = _learnedFlow.asSharedFlow()

        /** Restores a persisted centre; non-positive or out-of-range values (unset) are ignored. */
        fun load(frac: Float) {
            if (frac <= 0f || frac >= 1f) return
            centerFrac = frac
            learned = true
        }

        /** Forgets everything and returns to the default centre (e.g. after moving the phone). */
        fun reset() {
            tops.fill(0.0)
            bottoms.fill(0.0)
            mass = 0.0
            movingMs = 0L
            lastObservedMs = null
            lastUpdateMs = null
            outsideEventsMs.clear()
            centerFrac = DEFAULT_BAND_CENTER_FRAC
            learned = false
        }

        /**
         * Adds this frame's confident detections (frame-normalised boxes) while driving.
         * [bandHeightFrac] is how tall the active band model's strip is (null with a full-frame model).
         */
        fun observe(
            detections: List<Detection>,
            moving: Boolean,
            bandHeightFrac: Float?,
            nowMs: Long,
        ) {
            val previous = lastObservedMs
            lastObservedMs = nowMs
            if (!moving || previous == null) return
            val dt = (nowMs - previous).coerceIn(0L, MAX_STEP_MS)
            movingMs += dt
            decay(dt)
            for (d in detections) {
                if (!usable(d)) continue
                add(d, 1.0)
            }
            if (bandHeightFrac != null) maybeUpdate(bandHeightFrac, nowMs, force = false)
        }

        /** Detections a full-frame probe found outside the current strip: evidence the strip is misplaced. */
        fun observeOutside(
            detections: List<Detection>,
            bandHeightFrac: Float?,
            nowMs: Long,
        ) {
            if (bandHeightFrac == null) return
            for (d in detections) {
                if (!usable(d)) continue
                add(d, OUTSIDE_WEIGHT)
                outsideEventsMs.addLast(nowMs)
            }
            while (outsideEventsMs.isNotEmpty() && nowMs - outsideEventsMs.first() > OUTSIDE_WINDOW_MS) {
                outsideEventsMs.removeFirst()
            }
            if (outsideEventsMs.size >= OUTSIDE_EVENTS_TO_MOVE) {
                outsideEventsMs.clear()
                maybeUpdate(bandHeightFrac, nowMs, force = true)
            }
        }

        /** Confident boxes that are not clipped or resting on the bottom edge (that is the hood, not traffic). */
        private fun usable(d: Detection): Boolean =
            d.confidence >= ModelConfig.TRACK_START_CONFIDENCE &&
                !d.bottomClipped &&
                !HoodDetector.touchesBottom(d.boundingBox.bottom)

        private fun decay(dtMs: Long) {
            if (dtMs <= 0L) return
            val factor = 0.5.pow(dtMs.toDouble() / HALF_LIFE_MS)
            for (i in 0 until BINS) {
                tops[i] *= factor
                bottoms[i] *= factor
            }
            mass *= factor
        }

        private fun add(
            detection: Detection,
            weight: Double,
        ) {
            val box = detection.boundingBox
            tops[binOf(box.top / ModelConfig.FRAME_SPACE)] += weight
            bottoms[binOf(box.bottom / ModelConfig.FRAME_SPACE)] += weight
            mass += weight
        }

        private fun maybeUpdate(
            bandHeightFrac: Float,
            nowMs: Long,
            force: Boolean,
        ) {
            val lastUpdate = lastUpdateMs
            val enough = mass >= MIN_MASS && movingMs >= MIN_MOVING_MS
            val dueByTime = lastUpdate == null || nowMs - lastUpdate >= MIN_UPDATE_GAP_MS
            if (!enough || !(force || dueByTime)) return
            val estimate = estimateCenter(bandHeightFrac)
            if (learned && abs(estimate - centerFrac) < MIN_CHANGE_FRAC && !force) return
            lastUpdateMs = nowMs
            if (estimate != centerFrac || !learned) {
                centerFrac = estimate
                learned = true
                _learnedFlow.tryEmit(estimate)
            }
        }

        /** The strip centre covering the observed vehicles, preferring the bottom edge when the strip is too short. */
        internal fun estimateCenter(bandHeightFrac: Float): Float {
            val top = quantile(tops, TOP_QUANTILE) - TOP_MARGIN
            val bottom = quantile(bottoms, BOTTOM_QUANTILE) + BOTTOM_MARGIN
            val center = if (bottom - top <= bandHeightFrac) (top + bottom) / 2f else bottom - bandHeightFrac / 2f
            val clamped = center.coerceIn(bandHeightFrac / 2f, 1f - bandHeightFrac / 2f)
            return (clamped / QUANTUM).roundToInt() * QUANTUM
        }

        fun debugJson(): JSONObject =
            JSONObject()
                .put("centerFrac", centerFrac.toDouble())
                .put("learned", learned)
                .put("mass", mass)
                .put("movingSec", movingMs / MS_PER_SEC)
                .put("topP1", quantile(tops, TOP_QUANTILE).toDouble())
                .put("bottomP99", quantile(bottoms, BOTTOM_QUANTILE).toDouble())

        companion object {
            const val BINS = 240
            const val HALF_LIFE_MS = 300_000.0
            const val MIN_MASS = 1_500.0
            const val MIN_MOVING_MS = 120_000L
            const val MIN_UPDATE_GAP_MS = 30_000L
            const val MIN_CHANGE_FRAC = 24f / 480f
            const val QUANTUM = 16f / 480f
            const val TOP_QUANTILE = 0.01
            const val BOTTOM_QUANTILE = 0.99
            const val TOP_MARGIN = 26f / 480f
            const val BOTTOM_MARGIN = 24f / 480f
            const val OUTSIDE_WEIGHT = 30.0
            const val OUTSIDE_WINDOW_MS = 30_000L
            const val OUTSIDE_EVENTS_TO_MOVE = 3
            private const val MAX_STEP_MS = 1_000L
            private const val MS_PER_SEC = 1_000
        }
    }

private fun binOf(fraction: Float): Int = (fraction * BandLearner.BINS).toInt().coerceIn(0, BandLearner.BINS - 1)

/** The fraction of frame height below which a share [q] of the histogram's weight lies. */
private fun quantile(
    histogram: DoubleArray,
    q: Double,
): Float {
    val total = histogram.sum()
    if (total <= 0.0) return DEFAULT_BAND_CENTER_FRAC
    var running = 0.0
    for (i in histogram.indices) {
        running += histogram[i]
        if (running >= q * total) return (i + 0.5f) / BandLearner.BINS
    }
    return 1f
}
