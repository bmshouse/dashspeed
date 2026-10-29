package org.chaosorderx.dashspeed.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Learns which parts of the frame never change while the car is moving (hood, phone mount)
 * so detections sitting on them can be dropped, whatever the user's mounting setup.
 *
 * While driving straight above [MIN_SPEED_MPS], luma on a coarse grid is compared against a
 * snapshot taken [COMPARE_INTERVAL_MS] earlier. Scenery changes; hood and mount do not. Cells
 * are scored by their *worst* (highest) windowed mean change across [WINDOWS_REQUIRED] windows,
 * so a lead car that is present only part of the time cannot make a cell look static. Static
 * cells connected to the bottom/left/right frame edge form the mask (the top edge is excluded:
 * clear sky is also unchanging). Errors therefore fail safe — a missed mask only lets a hood
 * detection through, it never hides a car.
 */
@Singleton
class StaticMaskLearner
    @Inject
    constructor() {
        private val cellCount = StaticMask.COLS * StaticMask.ROWS

        // Lazy so the pure ingest() logic stays constructible in JVM unit tests.
        private val sampleBitmap by lazy {
            Bitmap.createBitmap(StaticMask.COLS, StaticMask.ROWS, Bitmap.Config.ARGB_8888)
        }
        private val sampleCanvas by lazy { Canvas(sampleBitmap) }
        private val samplePaint by lazy { Paint(Paint.FILTER_BITMAP_FLAG) }
        private val sampleRect by lazy { Rect(0, 0, StaticMask.COLS, StaticMask.ROWS) }
        private val pixels = IntArray(cellCount)
        private val luma = FloatArray(cellCount)

        private var learning: Boolean
            get() = _learning.value
            set(value) {
                _learning.value = value
            }
        private var reference: FloatArray? = null
        private var referenceMs = 0L
        private var windowSum = FloatArray(cellCount)
        private var windowSamples = 0
        private var worstWindowMean = FloatArray(cellCount)
        private var windowsDone = 0

        private val extraRegions = mutableListOf<HoodDetector.Zone>()

        /** Increments on every [reset], so dependants (the hood detector) can forget their own state too. */
        @Volatile
        var resetCount = 0
            private set

        private val _mask = MutableStateFlow(StaticMask())
        private val _learning = MutableStateFlow(true)

        /** Current mask; observable so the UI can draw it. */
        val maskFlow: StateFlow<StaticMask> = _mask.asStateFlow()

        /** True until a mask has been learned, restored or set by hand. */
        val learningFlow: StateFlow<Boolean> = _learning.asStateFlow()

        val mask: StaticMask get() = _mask.value

        private val _learned =
            MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Emits the serialised mask each time learning completes, for persistence. */
        val learned: Flow<String> = _learned.asSharedFlow()

        /** Restores a persisted mask and stops learning. Blank/invalid input is ignored. */
        fun load(bits: String) {
            val restored = StaticMask.deserialize(bits) ?: return
            _mask.value = restored
            learning = false
        }

        /** Installs a hand-painted mask, stops auto-learning and persists it via [learned]. */
        fun setManual(manual: StaticMask) {
            _mask.value = manual
            extraRegions.clear()
            learning = false
            clearAccumulators()
            _learned.tryEmit(manual.serialize())
        }

        /** Discards the mask and starts learning again (e.g. phone mount moved). */
        fun reset() {
            _mask.value = StaticMask()
            extraRegions.clear()
            resetCount++
            learning = true
            clearAccumulators()
        }

        /**
         * Marks a region that detections proved static (a hood box that never moved) and persists the result.
         * Kept separately so finishing the pixel-based learning does not discard it.
         */
        fun addRegion(zone: HoodDetector.Zone) {
            extraRegions += zone
            val merged = _mask.value.withRegion(zone.left, zone.top, zone.right, zone.bottom)
            _mask.value = merged
            _learned.tryEmit(merged.serialize())
        }

        fun observe(
            frame: Bitmap,
            moving: Boolean,
            nowMs: Long,
        ) {
            if (!learning) return
            if (!moving) {
                reference = null
                return
            }
            sampleCanvas.drawBitmap(frame, Rect(0, 0, frame.width, frame.height), sampleRect, samplePaint)
            sampleBitmap.getPixels(pixels, 0, StaticMask.COLS, 0, 0, StaticMask.COLS, StaticMask.ROWS)
            for (i in 0 until cellCount) {
                val p = pixels[i]
                luma[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
            }
            ingest(luma, nowMs)
        }

        /** Core of the learner, split from bitmap sampling so it is unit-testable. */
        internal fun ingest(
            grid: FloatArray,
            nowMs: Long,
        ) {
            val ref = reference
            if (ref == null) {
                reference = grid.copyOf()
                referenceMs = nowMs
            } else if (nowMs - referenceMs >= COMPARE_INTERVAL_MS) {
                for (i in 0 until cellCount) windowSum[i] += abs(grid[i] - ref[i])
                windowSamples++
                grid.copyInto(ref)
                referenceMs = nowMs
                if (windowSamples >= SAMPLES_PER_WINDOW) closeWindow()
            }
        }

        /** Learner state for the debug log: per-cell worst windowed change drives the mask. */
        fun debugJson(): JSONObject =
            JSONObject()
                .put("learning", learning)
                .put("windowsDone", windowsDone)
                .put("windowSamples", windowSamples)
                .put("staticLumaDelta", STATIC_LUMA_DELTA.toDouble())
                .put("cols", StaticMask.COLS)
                .put("worstWindowMean", JSONArray(worstWindowMean.map { it.toDouble() }))
                .put("currentWindowMean", JSONArray(windowSum.map { (it / maxOf(1, windowSamples)).toDouble() }))

        private fun closeWindow() {
            for (i in 0 until cellCount) {
                worstWindowMean[i] = maxOf(worstWindowMean[i], windowSum[i] / windowSamples)
            }
            windowSum = FloatArray(cellCount)
            windowSamples = 0
            if (++windowsDone >= WINDOWS_REQUIRED) finish()
        }

        private fun finish() {
            val learnedMask =
                extraRegions.fold(buildMask(worstWindowMean)) { m, z -> m.withRegion(z.left, z.top, z.right, z.bottom) }
            if (learnedMask.coveredFraction > MAX_COVERAGE) {
                // Implausibly large (e.g. a convoy lead car filled the view the whole time): retry.
                clearAccumulators()
                return
            }
            _mask.value = learnedMask
            learning = false
            _learned.tryEmit(learnedMask.serialize())
        }

        private fun clearAccumulators() {
            reference = null
            windowSum = FloatArray(cellCount)
            windowSamples = 0
            worstWindowMean = FloatArray(cellCount)
            windowsDone = 0
        }

        companion object {
            const val MIN_SPEED_MPS = 6.7f // ~15 mph
            const val COMPARE_INTERVAL_MS = 250L
            const val SAMPLES_PER_WINDOW = 80 // ≈ 20 s of driving per window
            const val WINDOWS_REQUIRED = 3
            const val STATIC_LUMA_DELTA = 6f
            const val MAX_COVERAGE = 0.4f

            /** Static cells (low worst-case change) flood-filled from the bottom, left and right edges. */
            fun buildMask(worstMean: FloatArray): StaticMask {
                val cols = StaticMask.COLS
                val rows = StaticMask.ROWS
                val isStatic = BooleanArray(cols * rows) { worstMean[it] < STATIC_LUMA_DELTA }
                val result = BooleanArray(cols * rows)
                val queue = ArrayDeque<Int>()

                fun visit(
                    col: Int,
                    row: Int,
                ) {
                    if (col !in 0 until cols || row !in 0 until rows) return
                    val idx = row * cols + col
                    if (isStatic[idx] && !result[idx]) {
                        result[idx] = true
                        queue.add(idx)
                    }
                }
                for (col in 0 until cols) visit(col, rows - 1)
                for (row in 0 until rows) {
                    visit(0, row)
                    visit(cols - 1, row)
                }
                while (queue.isNotEmpty()) {
                    val idx = queue.removeFirst()
                    val col = idx % cols
                    val row = idx / cols
                    visit(col - 1, row)
                    visit(col + 1, row)
                    visit(col, row - 1)
                    visit(col, row + 1)
                }
                return StaticMask(result)
            }
        }
    }
